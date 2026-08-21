package com.atlas.enterprise.task.storage;

import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskStep;
import com.atlas.enterprise.task.TaskStepName;
import com.atlas.enterprise.task.TaskStepStatus;
import com.atlas.enterprise.task.port.TaskStepRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTaskStepRepository implements TaskStepRepository {
    private static final String SELECT_COLUMNS = """
        SELECT task_step_id, task_id, step_name, sequence_no, status, attempt_no,
               input_hash, output_ref, trace_id, error_code, error_message,
               started_at, ended_at
          FROM task_step
        """;

    private final JdbcClient jdbc;

    public JdbcTaskStepRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public TaskStep start(
        UUID taskId,
        TaskStepName stepName,
        String inputHash,
        String traceId,
        Instant now
    ) {
        int attemptNo = jdbc.sql("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1
                  FROM task_step
                 WHERE task_id = :taskId
                   AND step_name = :stepName
                """)
            .param("taskId", taskId)
            .param("stepName", stepName.name())
            .query(Integer.class)
            .single();
        UUID taskStepId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO task_step (
                    task_step_id, task_id, step_name, sequence_no, status, attempt_no,
                    input_hash, trace_id, started_at
                ) VALUES (
                    :taskStepId, :taskId, :stepName, :sequenceNo, :status, :attemptNo,
                    :inputHash, :traceId, :startedAt
                )
                """)
            .param("taskStepId", taskStepId)
            .param("taskId", taskId)
            .param("stepName", stepName.name())
            .param("sequenceNo", stepName.sequenceNo())
            .param("status", TaskStepStatus.RUNNING.name())
            .param("attemptNo", attemptNo)
            .param("inputHash", inputHash)
            .param("traceId", traceId)
            .param("startedAt", utc(now))
            .update();
        return new TaskStep(
            taskStepId,
            taskId,
            stepName,
            stepName.sequenceNo(),
            TaskStepStatus.RUNNING,
            attemptNo,
            inputHash,
            null,
            traceId,
            null,
            null,
            now,
            null
        );
    }

    @Override
    public void complete(UUID taskStepId, String outputRef, Instant now) {
        finish(taskStepId, TaskStepStatus.COMPLETED, outputRef, null, null, now);
    }

    @Override
    public void skip(UUID taskStepId, String outputRef, Instant now) {
        finish(taskStepId, TaskStepStatus.SKIPPED, outputRef, null, null, now);
    }

    @Override
    public void fail(
        UUID taskStepId,
        TaskErrorCode errorCode,
        String errorMessage,
        Instant now
    ) {
        finish(taskStepId, TaskStepStatus.FAILED, null, errorCode, errorMessage, now);
    }

    @Override
    public Optional<TaskStep> findLatest(UUID taskId, TaskStepName stepName) {
        return jdbc.sql(SELECT_COLUMNS + """
             WHERE task_id = :taskId
               AND step_name = :stepName
             ORDER BY attempt_no DESC
             FETCH FIRST 1 ROW ONLY
            """)
            .param("taskId", taskId)
            .param("stepName", stepName.name())
            .query(this::map)
            .optional();
    }

    @Override
    public List<TaskStep> findByTaskId(UUID taskId) {
        return jdbc.sql(SELECT_COLUMNS + """
             WHERE task_id = :taskId
             ORDER BY sequence_no, attempt_no
            """)
            .param("taskId", taskId)
            .query(this::map)
            .list();
    }

    private void finish(
        UUID taskStepId,
        TaskStepStatus status,
        String outputRef,
        TaskErrorCode errorCode,
        String errorMessage,
        Instant now
    ) {
        jdbc.sql("""
                UPDATE task_step
                   SET status = :status,
                       output_ref = :outputRef,
                       error_code = :errorCode,
                       error_message = :errorMessage,
                       ended_at = :endedAt
                 WHERE task_step_id = :taskStepId
                """)
            .param("status", status.name())
            .param("outputRef", outputRef)
            .param("errorCode", errorCode == null ? null : errorCode.name())
            .param("errorMessage", errorMessage)
            .param("endedAt", utc(now))
            .param("taskStepId", taskStepId)
            .update();
    }

    private TaskStep map(ResultSet rs, int rowNum) throws SQLException {
        String errorCode = rs.getString("error_code");
        OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
        return new TaskStep(
            rs.getObject("task_step_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            TaskStepName.valueOf(rs.getString("step_name")),
            rs.getInt("sequence_no"),
            TaskStepStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_no"),
            rs.getString("input_hash"),
            rs.getString("output_ref"),
            rs.getString("trace_id"),
            errorCode == null ? null : TaskErrorCode.valueOf(errorCode),
            rs.getString("error_message"),
            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
            endedAt == null ? null : endedAt.toInstant()
        );
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
