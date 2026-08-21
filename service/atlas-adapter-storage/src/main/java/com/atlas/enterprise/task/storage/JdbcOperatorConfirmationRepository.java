package com.atlas.enterprise.task.storage;

import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.port.OperatorConfirmationRepository;
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
public class JdbcOperatorConfirmationRepository
    implements OperatorConfirmationRepository {

    private static final String SELECT = """
        SELECT confirmation_id, task_id, data_snapshot_id, score_snapshot_id,
               review_state_hash, confirmed_evidence_count,
               rejected_evidence_count, operator_id, note, confirmed_at
          FROM operator_confirmation
        """;

    private final JdbcClient jdbc;

    public JdbcOperatorConfirmationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OperatorConfirmation save(OperatorConfirmation confirmation) {
        jdbc.sql("""
                INSERT INTO operator_confirmation (
                    confirmation_id, task_id, data_snapshot_id, score_snapshot_id,
                    review_state_hash, confirmed_evidence_count,
                    rejected_evidence_count, operator_id, note, confirmed_at
                ) VALUES (
                    :confirmationId, :taskId, :dataSnapshotId, :scoreSnapshotId,
                    :reviewStateHash, :confirmedEvidenceCount,
                    :rejectedEvidenceCount, :operatorId, :note, :confirmedAt
                )
                """)
            .param("confirmationId", confirmation.confirmationId())
            .param("taskId", confirmation.taskId())
            .param("dataSnapshotId", confirmation.dataSnapshotId())
            .param("scoreSnapshotId", confirmation.scoreSnapshotId())
            .param("reviewStateHash", confirmation.reviewStateHash())
            .param(
                "confirmedEvidenceCount",
                confirmation.confirmedEvidenceCount()
            )
            .param(
                "rejectedEvidenceCount",
                confirmation.rejectedEvidenceCount()
            )
            .param("operatorId", confirmation.operatorId())
            .param("note", confirmation.note())
            .param(
                "confirmedAt",
                OffsetDateTime.ofInstant(
                    confirmation.confirmedAt(),
                    ZoneOffset.UTC
                )
            )
            .update();
        return confirmation;
    }

    @Override
    public Optional<OperatorConfirmation> findLatestByTaskId(UUID taskId) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY confirmed_at DESC, confirmation_id DESC
                 FETCH FIRST 1 ROW ONLY
                """)
            .param("taskId", taskId)
            .query(this::map)
            .optional();
    }

    @Override
    public Map<UUID, OperatorConfirmation> findLatestByTaskIds(
        List<UUID> taskIds
    ) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT confirmation_id, task_id, data_snapshot_id,
                       score_snapshot_id, review_state_hash,
                       confirmed_evidence_count, rejected_evidence_count,
                       operator_id, note, confirmed_at
                  FROM (
                      SELECT operator_confirmation.*,
                             ROW_NUMBER() OVER (
                                 PARTITION BY task_id
                                 ORDER BY confirmed_at DESC,
                                          confirmation_id DESC
                             ) AS row_no
                        FROM operator_confirmation
                       WHERE task_id IN (:taskIds)
                  ) ranked
                 WHERE row_no = 1
                """)
            .param("taskIds", taskIds)
            .query(this::map)
            .list()
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                OperatorConfirmation::taskId,
                Function.identity()
            ));
    }

    @Override
    public List<OperatorConfirmation> findByTaskId(UUID taskId) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY confirmed_at DESC, confirmation_id DESC
                """)
            .param("taskId", taskId)
            .query(this::map)
            .list();
    }

    private OperatorConfirmation map(ResultSet rs, int rowNum)
        throws SQLException {
        return new OperatorConfirmation(
            rs.getObject("confirmation_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("data_snapshot_id", UUID.class),
            rs.getObject("score_snapshot_id", UUID.class),
            rs.getString("review_state_hash"),
            rs.getInt("confirmed_evidence_count"),
            rs.getInt("rejected_evidence_count"),
            rs.getString("operator_id"),
            rs.getString("note"),
            rs.getObject("confirmed_at", OffsetDateTime.class).toInstant()
        );
    }
}
