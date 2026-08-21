package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationBinding;
import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationRelease;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.ConfigurationVersionStatus;
import com.atlas.enterprise.configuration.TaskConfigurationSnapshot;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigurationApplicationService {
    private static final java.util.Set<ConfigurationCategory> TASK_CONSUMED_CATEGORIES =
        EnumSet.of(
            ConfigurationCategory.RULES,
            ConfigurationCategory.SEARCH,
            ConfigurationCategory.MODEL,
            ConfigurationCategory.SKILL,
            ConfigurationCategory.REPORT_TEMPLATE
        );
    private final ConfigurationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final List<ConfigurationContentValidator> contentValidators;
    private final List<ConfigurationReleaseGuard> releaseGuards;

    public ConfigurationApplicationService(
        ConfigurationRepository repository,
        ObjectMapper objectMapper,
        Clock clock,
        List<ConfigurationContentValidator> contentValidators,
        List<ConfigurationReleaseGuard> releaseGuards
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.contentValidators = List.copyOf(contentValidators);
        this.releaseGuards = List.copyOf(releaseGuards);
    }

    @Transactional
    public ConfigurationOverview create(
        String configKey,
        ConfigurationCategory category,
        String displayName,
        String description,
        boolean secretConfig,
        String valueJson,
        String secretRef,
        String operatorId
    ) {
        if (repository.findDefinition(configKey).isPresent()) {
            throw new ConfigurationConflictException("Configuration already exists: " + configKey);
        }
        Instant now = clock.instant();
        UUID configId = UUID.randomUUID();
        String canonical = canonicalJson(valueJson);
        validateSecret(secretConfig, canonical, secretRef);
        ConfigurationDefinition definition = new ConfigurationDefinition(
            configId, configKey, category, displayName, description,
            secretConfig, operatorId, now
        );
        ConfigurationVersion version = version(
            configId, 1, canonical, secretRef, operatorId, now
        );
        repository.create(definition, version);
        return new ConfigurationOverview(definition, List.of(version), null);
    }

    @Transactional
    public ConfigurationVersion createDraft(
        String configKey,
        String valueJson,
        String secretRef,
        String operatorId
    ) {
        ConfigurationDefinition definition = requireDefinition(configKey);
        String canonical = canonicalJson(valueJson);
        validateSecret(definition.secretConfig(), canonical, secretRef);
        int nextVersion = repository.findVersions(definition.configId()).stream()
            .mapToInt(ConfigurationVersion::versionNo)
            .max().orElse(0) + 1;
        return repository.createDraft(version(
            definition.configId(), nextVersion, canonical,
            secretRef, operatorId, clock.instant()
        ));
    }

    @Transactional
    public ConfigurationVersion updateDraft(
        UUID versionId,
        long expectedRowVersion,
        String valueJson,
        String secretRef,
        String operatorId
    ) {
        ConfigurationVersion current = requireVersion(versionId);
        if (current.status() != ConfigurationVersionStatus.DRAFT) {
            throw new ConfigurationConflictException("Only a draft version can be edited");
        }
        ConfigurationDefinition definition = repository.findDefinitions().stream()
            .filter(item -> item.configId().equals(current.configId()))
            .findFirst()
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration definition not found for version " + versionId
            ));
        String canonical = canonicalJson(valueJson);
        String normalizedSecretRef = blankToNull(secretRef);
        validateSecret(definition.secretConfig(), canonical, normalizedSecretRef);
        return repository.updateDraft(
            versionId, expectedRowVersion, canonical, normalizedSecretRef,
            sha256(canonical + "|" + normalizedSecretRef), operatorId, clock.instant()
        );
    }

    @Transactional
    public ConfigurationVersion validate(
        UUID versionId,
        long expectedRowVersion,
        String operatorId
    ) {
        ConfigurationVersion version = requireVersion(versionId);
        ConfigurationDefinition definition = repository.findDefinitions().stream()
            .filter(item -> item.configId().equals(version.configId()))
            .findFirst()
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration definition not found for version " + versionId
            ));
        canonicalJson(version.valueJson());
        validateSecret(definition.secretConfig(), version.valueJson(), version.secretRef());
        if (version.status() != ConfigurationVersionStatus.DRAFT) {
            throw new ConfigurationConflictException("Only a draft version can be validated");
        }
        String businessMessage = contentValidators.stream()
            .filter(validator -> validator.supports(definition.category()))
            .map(validator -> validator.validate(definition.category(), version.valueJson()))
            .filter(message -> message != null && !message.isBlank())
            .reduce((left, right) -> left + "; " + right)
            .orElse("JSON schema and secret reference checks passed");
        return repository.markValidated(
            versionId, expectedRowVersion, operatorId,
            businessMessage, clock.instant()
        );
    }

    @Transactional
    public ConfigurationBinding publish(
        UUID versionId,
        String environment,
        String idempotencyKey,
        String operatorId
    ) {
        return release(versionId, environment, idempotencyKey, operatorId,
            ConfigurationRelease.Action.PUBLISH);
    }

    @Transactional
    public ConfigurationBinding rollback(
        UUID versionId,
        String environment,
        String idempotencyKey,
        String operatorId
    ) {
        return release(versionId, environment, idempotencyKey, operatorId,
            ConfigurationRelease.Action.ROLLBACK);
    }

    @Transactional(readOnly = true)
    public List<ConfigurationOverview> list(String environment) {
        Map<UUID, ConfigurationBinding> bindings = new LinkedHashMap<>();
        repository.findBindings(environment).forEach(binding ->
            bindings.put(binding.configId(), binding)
        );
        return repository.findDefinitions().stream()
            .sorted(Comparator.comparing(ConfigurationDefinition::configKey))
            .map(definition -> new ConfigurationOverview(
                definition,
                repository.findVersions(definition.configId()),
                bindings.get(definition.configId())
            )).toList();
    }

    @Transactional
    public TaskConfigurationSnapshot snapshotForTask(UUID taskId, String environment) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        return repository.findTaskSnapshot(taskId, normalizedEnvironment)
            .orElseGet(() -> {
                Map<UUID, ConfigurationDefinition> definitions = repository.findDefinitions()
                    .stream().collect(java.util.stream.Collectors.toMap(
                        ConfigurationDefinition::configId, item -> item
                    ));
                List<ConfigurationBinding> bindings = repository.findBindings(normalizedEnvironment)
                    .stream()
                    .filter(binding -> {
                        ConfigurationDefinition definition = definitions.get(binding.configId());
                        return definition != null
                            && TASK_CONSUMED_CATEGORIES.contains(definition.category());
                    })
                    .sorted(Comparator.comparing(binding -> binding.configId().toString()))
                    .toList();
                List<Map<String, Object>> manifest = bindings.stream().map(binding -> {
                    ConfigurationVersion version = requireVersion(binding.activeVersionId());
                    ConfigurationDefinition definition = definitions.get(binding.configId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("config_key", definition.configKey());
                    item.put("version_id", version.versionId().toString());
                    item.put("version_no", version.versionNo());
                    item.put("checksum", version.checksum());
                    return item;
                }).toList();
                String json = writeJson(manifest);
                return repository.saveTaskSnapshot(new TaskConfigurationSnapshot(
                    UUID.randomUUID(), taskId, normalizedEnvironment,
                    json, sha256(json), clock.instant()
                ));
            });
    }

    private ConfigurationBinding release(
        UUID versionId,
        String environment,
        String idempotencyKey,
        String operatorId,
        ConfigurationRelease.Action action
    ) {
        ConfigurationVersion target = requireVersion(versionId);
        if (action == ConfigurationRelease.Action.PUBLISH
            && target.status() != ConfigurationVersionStatus.VALIDATED) {
            throw new ConfigurationConflictException("Only a validated version can be published");
        }
        if (action == ConfigurationRelease.Action.PUBLISH) {
            ConfigurationDefinition definition = repository.findDefinitions().stream()
                .filter(item -> item.configId().equals(target.configId()))
                .findFirst()
                .orElseThrow(() -> new ConfigurationNotFoundException(
                    "Configuration definition not found for version " + versionId
                ));
            releaseGuards.stream()
                .filter(guard -> guard.supports(definition.category()))
                .forEach(guard -> guard.checkPublish(target, environment));
        }
        if (action == ConfigurationRelease.Action.ROLLBACK
            && target.status() != ConfigurationVersionStatus.PUBLISHED
            && target.status() != ConfigurationVersionStatus.INACTIVE) {
            throw new ConfigurationConflictException("Rollback target must have been published before");
        }
        String env = normalizeEnvironment(environment);
        ConfigurationBinding current = repository.findBinding(target.configId(), env).orElse(null);
        if (current != null && current.activeVersionId().equals(versionId)) {
            return current;
        }
        ConfigurationRelease existing = repository.findReleaseByIdempotencyKey(idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return repository.findBinding(target.configId(), env)
                .orElseThrow(() -> new ConfigurationConflictException(
                    "Release idempotency record exists without an active binding"
                ));
        }
        ConfigurationRelease release = new ConfigurationRelease(
            UUID.randomUUID(), target.configId(), env,
            current == null ? null : current.activeVersionId(),
            versionId, action, idempotencyKey, operatorId, clock.instant()
        );
        return repository.release(release, current == null ? -1 : current.rowVersion());
    }

    private ConfigurationDefinition requireDefinition(String key) {
        return repository.findDefinition(key).orElseThrow(() ->
            new ConfigurationNotFoundException("Configuration not found: " + key)
        );
    }

    private ConfigurationVersion requireVersion(UUID id) {
        return repository.findVersion(id).orElseThrow(() ->
            new ConfigurationNotFoundException("Configuration version not found: " + id)
        );
    }

    private ConfigurationVersion version(
        UUID configId, int versionNo, String canonicalJson,
        String secretRef, String operatorId, Instant at
    ) {
        return new ConfigurationVersion(
            UUID.randomUUID(), configId, versionNo,
            ConfigurationVersionStatus.DRAFT, canonicalJson,
            blankToNull(secretRef), sha256(canonicalJson + "|" + blankToNull(secretRef)),
            null, operatorId, at, null, null, null, null, 0
        );
    }

    private String canonicalJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(
                value == null || value.isBlank() ? "{}" : value
            );
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Configuration value must be a JSON object");
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Configuration value is not valid JSON", exception);
        }
    }

    private static void validateSecret(boolean secretConfig, String json, String secretRef) {
        if (secretConfig && (secretRef == null || secretRef.isBlank())) {
            throw new IllegalArgumentException("Secret configuration requires a server-side secret reference");
        }
        String lowered = json.toLowerCase();
        if (lowered.contains("\"apikey\"") || lowered.contains("\"api_key\"")
            || lowered.contains("\"password\"") || lowered.contains("\"secret\"")) {
            throw new IllegalArgumentException("Secret values must not be stored in configuration JSON");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize configuration manifest", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return "DEV";
        }
        return value.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
