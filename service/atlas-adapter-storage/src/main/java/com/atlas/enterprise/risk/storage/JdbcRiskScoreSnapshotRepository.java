package com.atlas.enterprise.risk.storage;

import com.atlas.enterprise.risk.RiskLevel;
import com.atlas.enterprise.risk.RiskRuleHit;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.port.RiskScoreSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
public class JdbcRiskScoreSnapshotRepository implements RiskScoreSnapshotRepository {
    private static final String SELECT = """
        SELECT score_snapshot_id, task_id, data_snapshot_id, legacy_score,
               rule_calculated_score, event_floor_score, original_score, manual_score,
               original_risk_level, manual_risk_level, rule_version, engine_version,
               input_hash, rule_hits_json, calculated_at
          FROM risk_score_snapshot
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRiskScoreSnapshotRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public RiskScoreSnapshot save(RiskScoreSnapshot snapshot) {
        jdbc.sql("""
                INSERT INTO risk_score_snapshot (
                    score_snapshot_id, task_id, data_snapshot_id, legacy_score,
                    rule_calculated_score, event_floor_score, original_score, manual_score,
                    original_risk_level, manual_risk_level, rule_version, engine_version,
                    input_hash, rule_hits_json, calculated_at
                ) VALUES (
                    :scoreSnapshotId, :taskId, :dataSnapshotId, :legacyScore,
                    :ruleScore, :eventFloor, :originalScore, :manualScore,
                    :originalLevel, :manualLevel, :ruleVersion, :engineVersion,
                    :inputHash, :ruleHits, :calculatedAt
                )
                """)
            .param("scoreSnapshotId", snapshot.scoreSnapshotId())
            .param("taskId", snapshot.taskId())
            .param("dataSnapshotId", snapshot.dataSnapshotId())
            .param("legacyScore", snapshot.legacyScore())
            .param("ruleScore", snapshot.ruleCalculatedScore())
            .param("eventFloor", snapshot.eventFloorScore())
            .param("originalScore", snapshot.originalScore())
            .param("manualScore", snapshot.manualScore())
            .param("originalLevel", snapshot.originalRiskLevel().name())
            .param("manualLevel", snapshot.manualRiskLevel().name())
            .param("ruleVersion", snapshot.ruleVersion())
            .param("engineVersion", snapshot.engineVersion())
            .param("inputHash", snapshot.inputHash())
            .param("ruleHits", json(snapshot.ruleHits()))
            .param(
                "calculatedAt",
                OffsetDateTime.ofInstant(snapshot.calculatedAt(), ZoneOffset.UTC)
            )
            .update();
        return snapshot;
    }

    @Override
    public Optional<RiskScoreSnapshot> findById(UUID scoreSnapshotId) {
        return jdbc.sql(SELECT + " WHERE score_snapshot_id = :scoreSnapshotId")
            .param("scoreSnapshotId", scoreSnapshotId)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<RiskScoreSnapshot> findLatestByTaskId(UUID taskId) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY calculated_at DESC, created_order DESC
                 FETCH FIRST 1 ROW ONLY
                """)
            .param("taskId", taskId)
            .query(this::map)
            .optional();
    }

    @Override
    public Map<UUID, RiskScoreSnapshot> findLatestByTaskIds(
        List<UUID> taskIds
    ) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT score_snapshot_id, task_id, data_snapshot_id,
                       legacy_score, rule_calculated_score, event_floor_score,
                       original_score, manual_score, original_risk_level,
                       manual_risk_level, rule_version, engine_version,
                       input_hash, rule_hits_json, calculated_at
                  FROM (
                      SELECT risk_score_snapshot.*,
                             ROW_NUMBER() OVER (
                                 PARTITION BY task_id
                                 ORDER BY calculated_at DESC,
                                          created_order DESC
                             ) AS row_no
                        FROM risk_score_snapshot
                       WHERE task_id IN (:taskIds)
                  ) ranked
                 WHERE row_no = 1
                """)
            .param("taskIds", taskIds)
            .query(this::map)
            .list()
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                RiskScoreSnapshot::taskId,
                Function.identity()
            ));
    }

    @Override
    public Optional<RiskScoreSnapshot> findByInputHash(UUID taskId, String inputHash) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                   AND input_hash = :inputHash
                 ORDER BY calculated_at DESC, created_order DESC
                 FETCH FIRST 1 ROW ONLY
                """)
            .param("taskId", taskId)
            .param("inputHash", inputHash)
            .query(this::map)
            .optional();
    }

    private RiskScoreSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new RiskScoreSnapshot(
            rs.getObject("score_snapshot_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("data_snapshot_id", UUID.class),
            normalized(rs.getBigDecimal("legacy_score")),
            normalized(rs.getBigDecimal("rule_calculated_score")),
            normalized(rs.getBigDecimal("event_floor_score")),
            normalized(rs.getBigDecimal("original_score")),
            normalized(rs.getBigDecimal("manual_score")),
            RiskLevel.valueOf(rs.getString("original_risk_level")),
            RiskLevel.valueOf(rs.getString("manual_risk_level")),
            rs.getString("rule_version"),
            rs.getString("engine_version"),
            rs.getString("input_hash"),
            readRuleHits(rs.getString("rule_hits_json")),
            rs.getObject("calculated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize risk score snapshot", exception);
        }
    }

    private List<RiskRuleHit> readRuleHits(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<RiskRuleHit>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize risk score snapshot", exception);
        }
    }
}
