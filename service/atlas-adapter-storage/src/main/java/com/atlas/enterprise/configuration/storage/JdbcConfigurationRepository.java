package com.atlas.enterprise.configuration.storage;

import com.atlas.enterprise.configuration.ConfigurationBinding;
import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationRelease;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.ConfigurationVersionStatus;
import com.atlas.enterprise.configuration.TaskConfigurationSnapshot;
import com.atlas.enterprise.configuration.application.ConfigurationConflictException;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcConfigurationRepository implements ConfigurationRepository {
    private static final String DEFINITION_SELECT = """
        SELECT config_id, config_key, category, display_name, description,
               secret_config, created_by, created_at
          FROM configuration_definition
        """;
    private static final String VERSION_SELECT = """
        SELECT version_id, config_id, version_no, status, value_json,
               secret_ref, checksum, validation_message, created_by, created_at,
               validated_by, validated_at, published_by, published_at, row_version
          FROM configuration_version
        """;
    private static final String BINDING_SELECT = """
        SELECT config_id, environment, active_version_id, row_version,
               updated_by, updated_at
          FROM configuration_binding
        """;

    private final JdbcClient jdbc;

    public JdbcConfigurationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(ConfigurationDefinition definition, ConfigurationVersion initialVersion) {
        jdbc.sql("""
                INSERT INTO configuration_definition (
                    config_id, config_key, category, display_name, description,
                    secret_config, created_by, created_at
                ) VALUES (
                    :configId, :configKey, :category, :displayName, :description,
                    :secretConfig, :createdBy, :createdAt
                )
                """)
            .param("configId", definition.configId())
            .param("configKey", definition.configKey())
            .param("category", definition.category().name())
            .param("displayName", definition.displayName())
            .param("description", definition.description())
            .param("secretConfig", definition.secretConfig())
            .param("createdBy", definition.createdBy())
            .param("createdAt", time(definition.createdAt()))
            .update();
        insertVersion(initialVersion);
        audit(
            definition.createdBy(),
            "configuration.created",
            "CONFIG_DEFINITION",
            definition.configId().toString(),
            initialVersion.checksum(),
            definition.createdAt(),
            UUID.randomUUID().toString()
        );
    }

    @Override
    public Optional<ConfigurationDefinition> findDefinition(String configKey) {
        return jdbc.sql(DEFINITION_SELECT + " WHERE config_key = :configKey")
            .param("configKey", configKey)
            .query(this::definition).optional();
    }

    @Override
    public List<ConfigurationDefinition> findDefinitions() {
        return jdbc.sql(DEFINITION_SELECT + " ORDER BY config_key")
            .query(this::definition).list();
    }

    @Override
    public Optional<ConfigurationVersion> findVersion(UUID versionId) {
        return jdbc.sql(VERSION_SELECT + " WHERE version_id = :versionId")
            .param("versionId", versionId)
            .query(this::version).optional();
    }

    @Override
    public List<ConfigurationVersion> findVersions(UUID configId) {
        return jdbc.sql(VERSION_SELECT + " WHERE config_id = :configId ORDER BY version_no DESC")
            .param("configId", configId)
            .query(this::version).list();
    }

    @Override
    public ConfigurationVersion createDraft(ConfigurationVersion draft) {
        try {
            insertVersion(draft);
            audit(
                draft.createdBy(),
                "configuration.draft.created",
                "CONFIG_VERSION",
                draft.versionId().toString(),
                draft.checksum(),
                draft.createdAt(),
                UUID.randomUUID().toString()
            );
            return draft;
        } catch (DataIntegrityViolationException exception) {
            throw new ConfigurationConflictException(
                "A configuration draft with the same version already exists"
            );
        }
    }

    @Override
    public ConfigurationVersion updateDraft(
        UUID versionId, long expectedRowVersion, String valueJson,
        String secretRef, String checksum, String operatorId, Instant at
    ) {
        int updated = jdbc.sql("""
                UPDATE configuration_version
                   SET value_json = :valueJson, secret_ref = :secretRef,
                       checksum = :checksum, validation_message = NULL,
                       validated_by = NULL, validated_at = NULL,
                       row_version = row_version + 1
                 WHERE version_id = :versionId
                   AND status = 'DRAFT'
                   AND row_version = :expectedRowVersion
                """)
            .param("valueJson", valueJson)
            .param("secretRef", secretRef)
            .param("checksum", checksum)
            .param("versionId", versionId)
            .param("expectedRowVersion", expectedRowVersion)
            .update();
        if (updated != 1) {
            throw new ConfigurationConflictException(
                "Configuration draft changed before the edit was saved"
            );
        }
        audit(
            operatorId,
            "configuration.draft.updated",
            "CONFIG_VERSION",
            versionId.toString(),
            checksum,
            at,
            UUID.randomUUID().toString()
        );
        return findVersion(versionId).orElseThrow();
    }

    @Override
    public ConfigurationVersion markValidated(
        UUID versionId, long expectedRowVersion, String operatorId,
        String message, Instant at
    ) {
        int updated = jdbc.sql("""
                UPDATE configuration_version
                   SET status = 'VALIDATED', validation_message = :message,
                       validated_by = :operatorId, validated_at = :at,
                       row_version = row_version + 1
                 WHERE version_id = :versionId
                   AND status = 'DRAFT'
                   AND row_version = :expectedRowVersion
                """)
            .param("message", message)
            .param("operatorId", operatorId)
            .param("at", time(at))
            .param("versionId", versionId)
            .param("expectedRowVersion", expectedRowVersion)
            .update();
        if (updated != 1) {
            throw new ConfigurationConflictException(
                "Configuration version changed before validation completed"
            );
        }
        ConfigurationVersion validated = findVersion(versionId).orElseThrow();
        audit(
            operatorId,
            "configuration.validated",
            "CONFIG_VERSION",
            versionId.toString(),
            validated.checksum(),
            at,
            UUID.randomUUID().toString()
        );
        return validated;
    }

    @Override
    @Transactional
    public ConfigurationBinding release(
        ConfigurationRelease release,
        long expectedBindingRowVersion
    ) {
        Optional<ConfigurationRelease> existing = findReleaseByIdempotencyKey(
            release.idempotencyKey()
        );
        if (existing.isPresent()) {
            return findBinding(release.configId(), release.environment()).orElseThrow();
        }

        if (release.fromVersionId() != null) {
            jdbc.sql("""
                    UPDATE configuration_version
                       SET status = 'INACTIVE', row_version = row_version + 1
                     WHERE version_id = :versionId
                    """)
                .param("versionId", release.fromVersionId()).update();
        }
        int targetUpdated = jdbc.sql("""
                UPDATE configuration_version
                   SET status = 'PUBLISHED', published_by = :operatorId,
                       published_at = :at, row_version = row_version + 1
                 WHERE version_id = :versionId
                   AND config_id = :configId
                """)
            .param("operatorId", release.operatorId())
            .param("at", time(release.occurredAt()))
            .param("versionId", release.toVersionId())
            .param("configId", release.configId())
            .update();
        if (targetUpdated != 1) {
            throw new ConfigurationConflictException("Publish target no longer exists");
        }

        if (expectedBindingRowVersion < 0) {
            try {
                jdbc.sql("""
                        INSERT INTO configuration_binding (
                            config_id, environment, active_version_id, row_version,
                            updated_by, updated_at
                        ) VALUES (
                            :configId, :environment, :versionId, 0,
                            :operatorId, :at
                        )
                        """)
                    .param("configId", release.configId())
                    .param("environment", release.environment())
                    .param("versionId", release.toVersionId())
                    .param("operatorId", release.operatorId())
                    .param("at", time(release.occurredAt()))
                    .update();
            } catch (DataIntegrityViolationException exception) {
                throw new ConfigurationConflictException(
                    "Another configuration version was published concurrently"
                );
            }
        } else {
            int bindingUpdated = jdbc.sql("""
                    UPDATE configuration_binding
                       SET active_version_id = :versionId,
                           row_version = row_version + 1,
                           updated_by = :operatorId, updated_at = :at
                     WHERE config_id = :configId AND environment = :environment
                       AND row_version = :expectedRowVersion
                    """)
                .param("versionId", release.toVersionId())
                .param("operatorId", release.operatorId())
                .param("at", time(release.occurredAt()))
                .param("configId", release.configId())
                .param("environment", release.environment())
                .param("expectedRowVersion", expectedBindingRowVersion)
                .update();
            if (bindingUpdated != 1) {
                throw new ConfigurationConflictException(
                    "Another configuration version was published concurrently"
                );
            }
        }

        jdbc.sql("""
                INSERT INTO configuration_release (
                    release_id, config_id, environment, from_version_id,
                    to_version_id, action, idempotency_key, operator_id, occurred_at
                ) VALUES (
                    :releaseId, :configId, :environment, :fromVersionId,
                    :toVersionId, :action, :idempotencyKey, :operatorId, :at
                )
                """)
            .param("releaseId", release.releaseId())
            .param("configId", release.configId())
            .param("environment", release.environment())
            .param("fromVersionId", release.fromVersionId())
            .param("toVersionId", release.toVersionId())
            .param("action", release.action().name())
            .param("idempotencyKey", release.idempotencyKey())
            .param("operatorId", release.operatorId())
            .param("at", time(release.occurredAt()))
            .update();
        audit(release);
        return findBinding(release.configId(), release.environment()).orElseThrow();
    }

    @Override
    public Optional<ConfigurationBinding> findBinding(UUID configId, String environment) {
        return jdbc.sql(BINDING_SELECT + " WHERE config_id = :configId AND environment = :environment")
            .param("configId", configId)
            .param("environment", environment.toUpperCase())
            .query(this::binding).optional();
    }

    @Override
    public List<ConfigurationBinding> findBindings(String environment) {
        return jdbc.sql(BINDING_SELECT + " WHERE environment = :environment ORDER BY config_id")
            .param("environment", environment.toUpperCase())
            .query(this::binding).list();
    }

    @Override
    public Optional<ConfigurationRelease> findReleaseByIdempotencyKey(String key) {
        return jdbc.sql("""
                SELECT release_id, config_id, environment, from_version_id,
                       to_version_id, action, idempotency_key, operator_id, occurred_at
                  FROM configuration_release
                 WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query(this::release).optional();
    }

    @Override
    public TaskConfigurationSnapshot saveTaskSnapshot(TaskConfigurationSnapshot snapshot) {
        try {
            jdbc.sql("""
                    INSERT INTO task_configuration_snapshot (
                        config_snapshot_id, task_id, environment,
                        manifest_json, content_hash, frozen_at
                    ) VALUES (
                        :id, :taskId, :environment, :manifest, :hash, :frozenAt
                    )
                    """)
                .param("id", snapshot.configSnapshotId())
                .param("taskId", snapshot.taskId())
                .param("environment", snapshot.environment())
                .param("manifest", snapshot.manifestJson())
                .param("hash", snapshot.contentHash())
                .param("frozenAt", time(snapshot.frozenAt()))
                .update();
            return snapshot;
        } catch (DataIntegrityViolationException exception) {
            return findTaskSnapshot(snapshot.taskId(), snapshot.environment()).orElseThrow();
        }
    }

    @Override
    public Optional<TaskConfigurationSnapshot> findTaskSnapshot(UUID taskId, String environment) {
        return jdbc.sql("""
                SELECT config_snapshot_id, task_id, environment,
                       manifest_json, content_hash, frozen_at
                  FROM task_configuration_snapshot
                 WHERE task_id = :taskId AND environment = :environment
                """)
            .param("taskId", taskId)
            .param("environment", environment.toUpperCase())
            .query(this::taskSnapshot).optional();
    }

    private void insertVersion(ConfigurationVersion version) {
        jdbc.sql("""
                INSERT INTO configuration_version (
                    version_id, config_id, version_no, status, value_json,
                    secret_ref, checksum, validation_message, created_by, created_at,
                    validated_by, validated_at, published_by, published_at, row_version
                ) VALUES (
                    :versionId, :configId, :versionNo, :status, :valueJson,
                    :secretRef, :checksum, :validationMessage, :createdBy, :createdAt,
                    :validatedBy, :validatedAt, :publishedBy, :publishedAt, :rowVersion
                )
                """)
            .param("versionId", version.versionId())
            .param("configId", version.configId())
            .param("versionNo", version.versionNo())
            .param("status", version.status().name())
            .param("valueJson", version.valueJson())
            .param("secretRef", version.secretRef())
            .param("checksum", version.checksum())
            .param("validationMessage", version.validationMessage())
            .param("createdBy", version.createdBy())
            .param("createdAt", time(version.createdAt()))
            .param("validatedBy", version.validatedBy())
            .param("validatedAt", nullableTime(version.validatedAt()))
            .param("publishedBy", version.publishedBy())
            .param("publishedAt", nullableTime(version.publishedAt()))
            .param("rowVersion", version.rowVersion())
            .update();
    }

    private void audit(ConfigurationRelease release) {
        ConfigurationVersion version = findVersion(release.toVersionId()).orElseThrow();
        audit(
            release.operatorId(),
            "configuration." + release.action().name().toLowerCase(),
            "CONFIG_VERSION",
            release.toVersionId().toString(),
            version.checksum(),
            release.occurredAt(),
            release.releaseId().toString()
        );
    }

    private void audit(
        String actorId,
        String action,
        String targetType,
        String targetId,
        String digest,
        Instant occurredAt,
        String traceId
    ) {
        jdbc.sql("""
                INSERT INTO audit_event (
                    task_id, trace_id, actor_type, actor_id, action,
                    target_type, target_id, payload_digest, occurred_at
                ) VALUES (
                    NULL, :traceId, 'OPERATOR', :actorId, :action,
                    :targetType, :targetId, :digest, :at
                )
                """)
            .param("traceId", traceId)
            .param("actorId", actorId)
            .param("action", action)
            .param("targetType", targetType)
            .param("targetId", targetId)
            .param("digest", digest)
            .param("at", time(occurredAt))
            .update();
    }

    private ConfigurationDefinition definition(ResultSet rs, int row) throws SQLException {
        return new ConfigurationDefinition(
            rs.getObject("config_id", UUID.class), rs.getString("config_key"),
            ConfigurationCategory.valueOf(rs.getString("category")),
            rs.getString("display_name"), rs.getString("description"),
            rs.getBoolean("secret_config"), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private ConfigurationVersion version(ResultSet rs, int row) throws SQLException {
        return new ConfigurationVersion(
            rs.getObject("version_id", UUID.class), rs.getObject("config_id", UUID.class),
            rs.getInt("version_no"), ConfigurationVersionStatus.valueOf(rs.getString("status")),
            rs.getString("value_json"), rs.getString("secret_ref"), rs.getString("checksum"),
            rs.getString("validation_message"), rs.getString("created_by"), instant(rs, "created_at"),
            rs.getString("validated_by"), instantNullable(rs, "validated_at"),
            rs.getString("published_by"), instantNullable(rs, "published_at"),
            rs.getLong("row_version")
        );
    }

    private ConfigurationBinding binding(ResultSet rs, int row) throws SQLException {
        return new ConfigurationBinding(
            rs.getObject("config_id", UUID.class), rs.getString("environment"),
            rs.getObject("active_version_id", UUID.class), rs.getLong("row_version"),
            rs.getString("updated_by"), instant(rs, "updated_at")
        );
    }

    private ConfigurationRelease release(ResultSet rs, int row) throws SQLException {
        return new ConfigurationRelease(
            rs.getObject("release_id", UUID.class), rs.getObject("config_id", UUID.class),
            rs.getString("environment"), rs.getObject("from_version_id", UUID.class),
            rs.getObject("to_version_id", UUID.class),
            ConfigurationRelease.Action.valueOf(rs.getString("action")),
            rs.getString("idempotency_key"), rs.getString("operator_id"), instant(rs, "occurred_at")
        );
    }

    private TaskConfigurationSnapshot taskSnapshot(ResultSet rs, int row) throws SQLException {
        return new TaskConfigurationSnapshot(
            rs.getObject("config_snapshot_id", UUID.class), rs.getObject("task_id", UUID.class),
            rs.getString("environment"), rs.getString("manifest_json"),
            rs.getString("content_hash"), instant(rs, "frozen_at")
        );
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableTime(Instant instant) {
        return instant == null ? null : time(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant instantNullable(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
