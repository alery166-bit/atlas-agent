package com.atlas.enterprise.task.storage;

import com.atlas.enterprise.task.SubjectDataConflictDecision;
import com.atlas.enterprise.task.SubjectDataConflictResolution;
import com.atlas.enterprise.task.port.SubjectDataConflictResolutionRepository;
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
public class JdbcSubjectDataConflictResolutionRepository
    implements SubjectDataConflictResolutionRepository {

    private static final String SELECT = """
        SELECT resolution_id, task_id, data_snapshot_id, decision, note,
               operator_id, resolved_at
          FROM subject_data_conflict_resolution
        """;

    private final JdbcClient jdbc;

    public JdbcSubjectDataConflictResolutionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SubjectDataConflictResolution save(
        SubjectDataConflictResolution resolution
    ) {
        jdbc.sql("""
                INSERT INTO subject_data_conflict_resolution (
                    resolution_id, task_id, data_snapshot_id, decision, note,
                    operator_id, resolved_at
                ) VALUES (
                    :resolutionId, :taskId, :dataSnapshotId, :decision, :note,
                    :operatorId, :resolvedAt
                )
                """)
            .param("resolutionId", resolution.resolutionId())
            .param("taskId", resolution.taskId())
            .param("dataSnapshotId", resolution.dataSnapshotId())
            .param("decision", resolution.decision().name())
            .param("note", resolution.note())
            .param("operatorId", resolution.operatorId())
            .param(
                "resolvedAt",
                OffsetDateTime.ofInstant(resolution.resolvedAt(), ZoneOffset.UTC)
            )
            .update();
        return resolution;
    }

    @Override
    public Optional<SubjectDataConflictResolution> findLatestByTaskId(
        UUID taskId
    ) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY resolved_at DESC, resolution_id DESC
                 FETCH FIRST 1 ROW ONLY
                """)
            .param("taskId", taskId)
            .query(this::map)
            .optional();
    }

    @Override
    public Map<UUID, SubjectDataConflictResolution> findLatestByTaskIds(
        List<UUID> taskIds
    ) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT resolution_id, task_id, data_snapshot_id, decision,
                       note, operator_id, resolved_at
                  FROM (
                      SELECT subject_data_conflict_resolution.*,
                             ROW_NUMBER() OVER (
                                 PARTITION BY task_id
                                 ORDER BY resolved_at DESC, resolution_id DESC
                             ) AS row_no
                        FROM subject_data_conflict_resolution
                       WHERE task_id IN (:taskIds)
                  ) ranked
                 WHERE row_no = 1
                """)
            .param("taskIds", taskIds)
            .query(this::map)
            .list()
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                SubjectDataConflictResolution::taskId,
                Function.identity()
            ));
    }

    private SubjectDataConflictResolution map(ResultSet rs, int rowNum)
        throws SQLException {
        return new SubjectDataConflictResolution(
            rs.getObject("resolution_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("data_snapshot_id", UUID.class),
            SubjectDataConflictDecision.valueOf(rs.getString("decision")),
            rs.getString("note"),
            rs.getString("operator_id"),
            rs.getObject("resolved_at", OffsetDateTime.class).toInstant()
        );
    }
}
