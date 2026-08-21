package com.atlas.enterprise.risk.storage;

import com.atlas.enterprise.risk.RiskRuleReplayRun;
import com.atlas.enterprise.risk.port.RiskRuleReplayRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiskRuleReplayRepository implements RiskRuleReplayRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRiskRuleReplayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RiskRuleReplayRun save(RiskRuleReplayRun run) {
        jdbc.update(
            """
            INSERT INTO risk_rule_replay_run (
                replay_id, version_id, version_checksum, status, sample_count, passed_count,
                score_changed_count, level_changed_count, max_score_delta,
                result_json, operator_id, created_at
            ) VALUES (
                :replayId, :versionId, :versionChecksum, :status, :sampleCount, :passedCount,
                :scoreChangedCount, :levelChangedCount, :maxScoreDelta,
                :resultJson, :operatorId, :createdAt
            )
            """,
            Map.ofEntries(
                Map.entry("replayId", run.replayId()),
                Map.entry("versionId", run.versionId()),
                Map.entry("versionChecksum", run.versionChecksum()),
                Map.entry("status", run.status().name()),
                Map.entry("sampleCount", run.sampleCount()),
                Map.entry("passedCount", run.passedCount()),
                Map.entry("scoreChangedCount", run.scoreChangedCount()),
                Map.entry("levelChangedCount", run.levelChangedCount()),
                Map.entry("maxScoreDelta", run.maxScoreDelta()),
                Map.entry("resultJson", run.resultJson()),
                Map.entry("operatorId", run.operatorId()),
                Map.entry("createdAt", run.createdAt())
            )
        );
        jdbc.update(
            """
            INSERT INTO audit_event (
                task_id, trace_id, actor_type, actor_id, action,
                target_type, target_id, payload_digest, occurred_at
            ) VALUES (
                NULL, :traceId, 'OPERATOR', :operatorId, 'risk_rule.replay',
                'CONFIGURATION_VERSION', :targetId, :digest, :occurredAt
            )
            """,
            Map.of(
                "traceId", "rule-replay:" + run.replayId(),
                "operatorId", run.operatorId(),
                "targetId", run.versionId().toString(),
                "digest", run.status().name() + ":" + run.sampleCount() + ":" + run.passedCount(),
                "occurredAt", run.createdAt()
            )
        );
        return run;
    }

    @Override
    public Optional<RiskRuleReplayRun> findLatest(UUID versionId) {
        return jdbc.query(
            """
            SELECT * FROM risk_rule_replay_run
             WHERE version_id = :versionId
             ORDER BY created_at DESC, replay_id DESC
             FETCH FIRST 1 ROWS ONLY
            """,
            Map.of("versionId", versionId),
            this::map
        ).stream().findFirst();
    }

    @Override
    public List<RiskRuleReplayRun> findByVersion(UUID versionId) {
        return jdbc.query(
            """
            SELECT * FROM risk_rule_replay_run
             WHERE version_id = :versionId
             ORDER BY created_at DESC, replay_id DESC
            """,
            Map.of("versionId", versionId),
            this::map
        );
    }

    @Override
    public long countTasksUsingVersion(UUID versionId) {
        Long value = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM task_configuration_snapshot
             WHERE manifest_json LIKE :needle
            """,
            Map.of("needle", "%\"version_id\":\"" + versionId + "\"%"),
            Long.class
        );
        return value == null ? 0 : value;
    }

    private RiskRuleReplayRun map(ResultSet rs, int rowNum) throws SQLException {
        return new RiskRuleReplayRun(
            rs.getObject("replay_id", UUID.class),
            rs.getObject("version_id", UUID.class),
            rs.getString("version_checksum"),
            RiskRuleReplayRun.Status.valueOf(rs.getString("status")),
            rs.getInt("sample_count"),
            rs.getInt("passed_count"),
            rs.getInt("score_changed_count"),
            rs.getInt("level_changed_count"),
            rs.getBigDecimal("max_score_delta"),
            rs.getString("result_json"),
            rs.getString("operator_id"),
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
