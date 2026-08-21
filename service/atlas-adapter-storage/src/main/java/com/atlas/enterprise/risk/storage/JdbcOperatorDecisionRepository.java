package com.atlas.enterprise.risk.storage;

import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskAdjustmentReason;
import com.atlas.enterprise.risk.port.OperatorDecisionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOperatorDecisionRepository implements OperatorDecisionRepository {
    private final JdbcClient jdbc;

    public JdbcOperatorDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OperatorDecision save(OperatorDecision decision) {
        jdbc.sql("""
                INSERT INTO operator_decision (
                    decision_id, task_id, target_type, target_id, decision_type,
                    before_json, after_json, reason_code, reason_text, operator_id, created_at
                ) VALUES (
                    :decisionId, :taskId, :targetType, :targetId, :decisionType,
                    :beforeJson, :afterJson, :reasonCode, :reasonText, :operatorId, :createdAt
                )
                """)
            .param("decisionId", decision.decisionId())
            .param("taskId", decision.taskId())
            .param("targetType", decision.targetType())
            .param("targetId", decision.targetId())
            .param("decisionType", decision.decisionType())
            .param("beforeJson", decision.beforeJson())
            .param("afterJson", decision.afterJson())
            .param("reasonCode", decision.reasonCode().name())
            .param("reasonText", decision.reasonText())
            .param("operatorId", decision.operatorId())
            .param(
                "createdAt",
                OffsetDateTime.ofInstant(decision.createdAt(), ZoneOffset.UTC)
            )
            .update();
        return decision;
    }

    @Override
    public List<OperatorDecision> findByTaskId(UUID taskId) {
        return jdbc.sql("""
                SELECT decision_id, task_id, target_type, target_id, decision_type,
                       before_json, after_json, reason_code, reason_text, operator_id, created_at
                  FROM operator_decision
                 WHERE task_id = :taskId
                 ORDER BY created_at, decision_id
                """)
            .param("taskId", taskId)
            .query(this::map)
            .list();
    }

    @Override
    public List<OperatorDecision> findByTaskIds(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT decision_id, task_id, target_type, target_id,
                       decision_type, before_json, after_json, reason_code,
                       reason_text, operator_id, created_at
                  FROM operator_decision
                 WHERE task_id IN (:taskIds)
                 ORDER BY task_id, created_at, decision_id
                """)
            .param("taskIds", taskIds)
            .query(this::map)
            .list();
    }

    private OperatorDecision map(ResultSet rs, int rowNum) throws SQLException {
        return new OperatorDecision(
            rs.getObject("decision_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getString("target_type"),
            rs.getObject("target_id", UUID.class),
            rs.getString("decision_type"),
            rs.getString("before_json"),
            rs.getString("after_json"),
            RiskAdjustmentReason.valueOf(rs.getString("reason_code")),
            rs.getString("reason_text"),
            rs.getString("operator_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }
}
