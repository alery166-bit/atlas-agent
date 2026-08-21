package com.atlas.enterprise.configuration.storage;

import com.atlas.enterprise.configuration.ConnectorTestRun;
import com.atlas.enterprise.configuration.port.ConnectorTestRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConnectorTestRepository implements ConnectorTestRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcConnectorTestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConnectorTestRun save(ConnectorTestRun run) {
        jdbc.update("""
            INSERT INTO connector_test_run (
                test_id, version_id, version_checksum, status, latency_ms,
                message, preview_json, operator_id, created_at
            ) VALUES (
                :testId, :versionId, :checksum, :status, :latency,
                :message, :preview, :operator, :createdAt
            )
            """, Map.ofEntries(
                Map.entry("testId", run.testId()),
                Map.entry("versionId", run.versionId()),
                Map.entry("checksum", run.versionChecksum()),
                Map.entry("status", run.status().name()),
                Map.entry("latency", run.latencyMs()),
                Map.entry("message", run.message()),
                Map.entry("preview", run.previewJson() == null ? "" : run.previewJson()),
                Map.entry("operator", run.operatorId()),
                // PostgreSQL JDBC cannot infer a SQL type for java.time.Instant when it is
                // supplied through a generic named-parameter map. Bind a JDBC timestamp
                // explicitly so connector tests work in the Docker/PostgreSQL profile too.
                Map.entry("createdAt", Timestamp.from(run.createdAt()))
            ));
        return run;
    }

    @Override
    public Optional<ConnectorTestRun> findLatest(UUID versionId) {
        return jdbc.query("""
            SELECT * FROM connector_test_run WHERE version_id = :versionId
             ORDER BY created_at DESC, test_id DESC FETCH FIRST 1 ROWS ONLY
            """, Map.of("versionId", versionId), this::map).stream().findFirst();
    }

    @Override
    public List<ConnectorTestRun> findByVersion(UUID versionId) {
        return jdbc.query("""
            SELECT * FROM connector_test_run WHERE version_id = :versionId
             ORDER BY created_at DESC, test_id DESC
            """, Map.of("versionId", versionId), this::map);
    }

    private ConnectorTestRun map(ResultSet rs, int rowNum) throws SQLException {
        String preview = rs.getString("preview_json");
        return new ConnectorTestRun(
            rs.getObject("test_id", UUID.class),
            rs.getObject("version_id", UUID.class),
            rs.getString("version_checksum"),
            ConnectorTestRun.Status.valueOf(rs.getString("status")),
            rs.getLong("latency_ms"), rs.getString("message"),
            preview == null || preview.isBlank() ? null : preview,
            rs.getString("operator_id"), rs.getTimestamp("created_at").toInstant()
        );
    }
}
