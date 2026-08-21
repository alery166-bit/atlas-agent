package com.atlas.enterprise.risk.storage;

import com.atlas.enterprise.risk.RiskAssessmentLabel;
import com.atlas.enterprise.risk.RiskAssessmentRevision;
import com.atlas.enterprise.risk.RiskAssessmentTrigger;
import com.atlas.enterprise.risk.RiskLevel;
import com.atlas.enterprise.risk.port.RiskAssessmentRevisionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiskAssessmentRevisionRepository
    implements RiskAssessmentRevisionRepository {
    private static final String SELECT = """
        SELECT assessment_revision_id, task_id, score_snapshot_id, data_snapshot_id,
               revision_no, trigger_type, legacy_score, rule_calculated_score,
               event_floor_score, original_score, final_score,
               original_risk_level, final_risk_level, rule_version, engine_version,
               source_labels_json, rule_labels_json, model_labels_json, final_labels_json,
               actor_type, actor_id, reason_code, reason_text, created_at
          FROM risk_assessment_revision
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRiskAssessmentRevisionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public RiskAssessmentRevision save(RiskAssessmentRevision revision) {
        jdbc.sql("""
                INSERT INTO risk_assessment_revision (
                    assessment_revision_id, task_id, score_snapshot_id, data_snapshot_id,
                    revision_no, trigger_type, legacy_score, rule_calculated_score,
                    event_floor_score, original_score, final_score,
                    original_risk_level, final_risk_level, rule_version, engine_version,
                    source_labels_json, rule_labels_json, model_labels_json, final_labels_json,
                    actor_type, actor_id, reason_code, reason_text, created_at
                ) VALUES (
                    :id, :taskId, :scoreSnapshotId, :dataSnapshotId,
                    :revisionNo, :triggerType, :legacyScore, :ruleScore,
                    :eventFloor, :originalScore, :finalScore,
                    :originalLevel, :finalLevel, :ruleVersion, :engineVersion,
                    :sourceLabels, :ruleLabels, :modelLabels, :finalLabels,
                    :actorType, :actorId, :reasonCode, :reasonText, :createdAt
                )
                """)
            .param("id", revision.assessmentRevisionId())
            .param("taskId", revision.taskId())
            .param("scoreSnapshotId", revision.scoreSnapshotId())
            .param("dataSnapshotId", revision.dataSnapshotId())
            .param("revisionNo", revision.revisionNo())
            .param("triggerType", revision.triggerType().name())
            .param("legacyScore", revision.legacyScore())
            .param("ruleScore", revision.ruleCalculatedScore())
            .param("eventFloor", revision.eventFloorScore())
            .param("originalScore", revision.originalScore())
            .param("finalScore", revision.finalScore())
            .param("originalLevel", revision.originalRiskLevel().name())
            .param("finalLevel", revision.finalRiskLevel().name())
            .param("ruleVersion", revision.ruleVersion())
            .param("engineVersion", revision.engineVersion())
            .param("sourceLabels", json(revision.sourceLabels()))
            .param("ruleLabels", json(revision.ruleLabels()))
            .param("modelLabels", json(revision.modelLabels()))
            .param("finalLabels", json(revision.finalLabels()))
            .param("actorType", revision.actorType())
            .param("actorId", revision.actorId())
            .param("reasonCode", revision.reasonCode())
            .param("reasonText", revision.reasonText())
            .param("createdAt", OffsetDateTime.ofInstant(revision.createdAt(), ZoneOffset.UTC))
            .update();
        return revision;
    }

    @Override
    public List<RiskAssessmentRevision> findByTaskId(UUID taskId) {
        return jdbc.sql(SELECT + " WHERE task_id = :taskId ORDER BY revision_no DESC")
            .param("taskId", taskId).query(this::map).list();
    }

    @Override
    public Optional<RiskAssessmentRevision> findLatestByTaskId(UUID taskId) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY revision_no DESC FETCH FIRST 1 ROW ONLY
                """)
            .param("taskId", taskId).query(this::map).optional();
    }

    @Override
    public Optional<RiskAssessmentRevision> findSystemRevisionByScoreSnapshotId(
        UUID scoreSnapshotId
    ) {
        return jdbc.sql(SELECT + """
                 WHERE score_snapshot_id = :scoreSnapshotId
                   AND trigger_type = 'SYSTEM_CALCULATION'
                 ORDER BY revision_no DESC FETCH FIRST 1 ROW ONLY
                """)
            .param("scoreSnapshotId", scoreSnapshotId).query(this::map).optional();
    }

    @Override
    public int nextRevisionNo(UUID taskId) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(revision_no), 0) + 1
                  FROM risk_assessment_revision WHERE task_id = :taskId
                """)
            .param("taskId", taskId).query(Integer.class).single();
    }

    private RiskAssessmentRevision map(ResultSet rs, int rowNum) throws SQLException {
        return new RiskAssessmentRevision(
            rs.getObject("assessment_revision_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("score_snapshot_id", UUID.class),
            rs.getObject("data_snapshot_id", UUID.class),
            rs.getInt("revision_no"),
            RiskAssessmentTrigger.valueOf(rs.getString("trigger_type")),
            rs.getBigDecimal("legacy_score"),
            rs.getBigDecimal("rule_calculated_score"),
            rs.getBigDecimal("event_floor_score"),
            rs.getBigDecimal("original_score"),
            rs.getBigDecimal("final_score"),
            RiskLevel.valueOf(rs.getString("original_risk_level")),
            RiskLevel.valueOf(rs.getString("final_risk_level")),
            rs.getString("rule_version"), rs.getString("engine_version"),
            labels(rs.getString("source_labels_json")),
            labels(rs.getString("rule_labels_json")),
            labels(rs.getString("model_labels_json")),
            labels(rs.getString("final_labels_json")),
            rs.getString("actor_type"), rs.getString("actor_id"),
            rs.getString("reason_code"), rs.getString("reason_text"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize assessment labels", exception);
        }
    }

    private List<RiskAssessmentLabel> labels(String value) {
        try {
            return objectMapper.readValue(
                value, new TypeReference<List<RiskAssessmentLabel>>() {}
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize assessment labels", exception);
        }
    }
}
