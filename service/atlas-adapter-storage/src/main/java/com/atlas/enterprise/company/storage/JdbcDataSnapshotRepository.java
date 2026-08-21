package com.atlas.enterprise.company.storage;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceStatus;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDataSnapshotRepository implements DataSnapshotRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDataSnapshotRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSnapshot save(DataSnapshot snapshot) {
        jdbc.sql("""
                INSERT INTO data_snapshot (
                    snapshot_id, task_id, atlas_company_id, snapshot_version,
                    company_facts_json, company_changes_json, risk_events_json,
                    source_status_json, content_hash, frozen_at
                ) VALUES (
                    :snapshotId, :taskId, :companyId, :snapshotVersion,
                    :companyFacts, :companyChanges, :riskEvents,
                    :sourceStatuses, :contentHash, :frozenAt
                )
                """)
            .param("snapshotId", snapshot.snapshotId())
            .param("taskId", snapshot.taskId())
            .param("companyId", snapshot.atlasCompanyId())
            .param("snapshotVersion", snapshot.snapshotVersion())
            .param("companyFacts", json(snapshot.companyFacts()))
            .param("companyChanges", json(snapshot.companyChanges()))
            .param("riskEvents", json(snapshot.riskEvents()))
            .param("sourceStatuses", json(snapshot.sourceStatuses()))
            .param("contentHash", snapshot.contentHash())
            .param("frozenAt", OffsetDateTime.ofInstant(snapshot.frozenAt(), ZoneOffset.UTC))
            .update();
        return snapshot;
    }

    @Override
    public Optional<DataSnapshot> findById(UUID snapshotId) {
        return jdbc.sql("""
                SELECT snapshot_id, task_id, atlas_company_id, snapshot_version,
                       company_facts_json, company_changes_json, risk_events_json,
                       source_status_json, content_hash, frozen_at
                  FROM data_snapshot
                 WHERE snapshot_id = :snapshotId
                """)
            .param("snapshotId", snapshotId)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<DataSnapshot> findLatestByTaskId(UUID taskId) {
        return jdbc.sql("""
                SELECT snapshot_id, task_id, atlas_company_id, snapshot_version,
                       company_facts_json, company_changes_json, risk_events_json,
                       source_status_json, content_hash, frozen_at
                  FROM data_snapshot
                 WHERE task_id = :taskId
                 ORDER BY snapshot_version DESC
                 FETCH FIRST 1 ROW ONLY
                """)
            .param("taskId", taskId)
            .query(this::map)
            .optional();
    }

    @Override
    public Map<UUID, DataSnapshot> findLatestByTaskIds(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT snapshot_id, task_id, atlas_company_id, snapshot_version,
                       company_facts_json, company_changes_json, risk_events_json,
                       source_status_json, content_hash, frozen_at
                  FROM (
                      SELECT data_snapshot.*,
                             ROW_NUMBER() OVER (
                                 PARTITION BY task_id
                                 ORDER BY snapshot_version DESC
                             ) AS row_no
                        FROM data_snapshot
                       WHERE task_id IN (:taskIds)
                  ) ranked
                 WHERE row_no = 1
                """)
            .param("taskIds", taskIds)
            .query(this::map)
            .list()
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                DataSnapshot::taskId,
                Function.identity()
            ));
    }

    @Override
    public int nextVersion(UUID taskId) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(snapshot_version), 0) + 1
                  FROM data_snapshot
                 WHERE task_id = :taskId
                """)
            .param("taskId", taskId)
            .query(Integer.class)
            .single();
    }

    private DataSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new DataSnapshot(
            rs.getObject("snapshot_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("atlas_company_id", UUID.class),
            rs.getInt("snapshot_version"),
            read(rs.getString("company_facts_json"), CompanyFacts.class),
            read(rs.getString("company_changes_json"), new TypeReference<List<CompanyChange>>() {}),
            read(rs.getString("risk_events_json"), new TypeReference<List<RiskEvent>>() {}),
            read(rs.getString("source_status_json"), new TypeReference<List<SourceStatus>>() {}),
            rs.getString("content_hash"),
            rs.getObject("frozen_at", OffsetDateTime.class).toInstant()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize data snapshot", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize data snapshot", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize data snapshot", exception);
        }
    }
}
