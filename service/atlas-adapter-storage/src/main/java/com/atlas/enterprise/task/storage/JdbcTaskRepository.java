package com.atlas.enterprise.task.storage;

import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskIntent;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.port.TaskRepository;
import com.atlas.enterprise.task.port.TaskSearchCriteria;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaskRepository implements TaskRepository {
    private static final String SELECT_COLUMNS = """
        SELECT task_id, task_no, intent, status, original_prompt, company_query,
               previous_report_file_id, operator_id, idempotency_key, atlas_company_id, current_step,
               failed_step, error_code, created_at, updated_at, completed_at
          FROM investigation_task
        """;

    private final JdbcClient jdbc;

    public JdbcTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InvestigationTask save(InvestigationTask task) {
        int updated = jdbc.sql("""
                UPDATE investigation_task
                   SET status = :status,
                       atlas_company_id = :atlasCompanyId,
                       current_step = :currentStep,
                       failed_step = :failedStep,
                       error_code = :errorCode,
                       updated_at = :updatedAt,
                       completed_at = :completedAt,
                       row_version = row_version + 1
                 WHERE task_id = :taskId
                """)
            .param("status", task.status().name())
            .param("atlasCompanyId", task.atlasCompanyId())
            .param("currentStep", task.currentStep())
            .param("failedStep", task.failedStep())
            .param("errorCode", task.errorCode() == null ? null : task.errorCode().name())
            .param("updatedAt", utc(task.updatedAt()))
            .param("completedAt", task.completedAt() == null ? null : utc(task.completedAt()))
            .param("taskId", task.taskId())
            .update();

        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO investigation_task (
                        task_id, task_no, intent, status, original_prompt, company_query,
                        previous_report_file_id, operator_id, idempotency_key, atlas_company_id, current_step,
                        failed_step, error_code, created_at, updated_at, completed_at
                    ) VALUES (
                        :taskId, :taskNo, :intent, :status, :prompt, :companyQuery,
                        :previousReportFileId, :operatorId, :idempotencyKey, :atlasCompanyId,
                        :currentStep, :failedStep, :errorCode, :createdAt, :updatedAt, :completedAt
                    )
                    """)
                .param("taskId", task.taskId())
                .param("taskNo", task.taskNo())
                .param("intent", task.intent().name())
                .param("status", task.status().name())
                .param("prompt", task.originalPrompt())
                .param("companyQuery", task.companyQuery())
                .param("previousReportFileId", task.previousReportFileId())
                .param("operatorId", task.operatorId())
                .param("idempotencyKey", task.idempotencyKey())
                .param("atlasCompanyId", task.atlasCompanyId())
                .param("currentStep", task.currentStep())
                .param("failedStep", task.failedStep())
                .param("errorCode", task.errorCode() == null ? null : task.errorCode().name())
                .param("createdAt", utc(task.createdAt()))
                .param("updatedAt", utc(task.updatedAt()))
                .param("completedAt", task.completedAt() == null ? null : utc(task.completedAt()))
                .update();
        }
        return task;
    }

    @Override
    public Optional<InvestigationTask> findById(UUID taskId) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE task_id = :taskId")
            .param("taskId", taskId)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<InvestigationTask> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE idempotency_key = :idempotencyKey")
            .param("idempotencyKey", idempotencyKey)
            .query(this::map)
            .optional();
    }

    @Override
    public List<InvestigationTask> search(TaskSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
            .append(" WHERE 1 = 1");
        if (criteria.query() != null) {
            sql.append("""
                 AND (
                     LOWER(task_no) LIKE :query
                     OR LOWER(company_query) LIKE :query
                     OR LOWER(original_prompt) LIKE :query
                 )
                """);
        }
        if (!criteria.statuses().isEmpty()) {
            sql.append(" AND status IN (:statuses)");
        }
        if (criteria.operatorId() != null) {
            sql.append(" AND operator_id = :operatorId");
        }
        if (criteria.cursorUpdatedAt() != null) {
            sql.append("""
                 AND (
                     updated_at < :cursorUpdatedAt
                     OR (updated_at = :cursorUpdatedAt AND task_id < :cursorTaskId)
                 )
                """);
        }
        sql.append(" ORDER BY updated_at DESC, task_id DESC LIMIT :limit");

        StatementSpec statement = jdbc.sql(sql.toString());
        if (criteria.query() != null) {
            statement = statement.param(
                "query",
                "%" + criteria.query().toLowerCase(java.util.Locale.ROOT) + "%"
            );
        }
        if (!criteria.statuses().isEmpty()) {
            statement = statement.param(
                "statuses",
                criteria.statuses().stream().map(Enum::name).toList()
            );
        }
        if (criteria.operatorId() != null) {
            statement = statement.param("operatorId", criteria.operatorId());
        }
        if (criteria.cursorUpdatedAt() != null) {
            statement = statement
                .param("cursorUpdatedAt", utc(criteria.cursorUpdatedAt()))
                .param("cursorTaskId", criteria.cursorTaskId());
        }
        return statement.param("limit", criteria.limit())
            .query(this::map)
            .list();
    }

    private InvestigationTask map(ResultSet rs, int rowNum) throws SQLException {
        String errorCode = rs.getString("error_code");
        OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
        return InvestigationTask.restore(
            rs.getObject("task_id", UUID.class),
            rs.getString("task_no"),
            TaskIntent.valueOf(rs.getString("intent")),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getString("original_prompt"),
            rs.getString("company_query"),
            rs.getString("previous_report_file_id"),
            rs.getString("operator_id"),
            rs.getString("idempotency_key"),
            rs.getObject("atlas_company_id", UUID.class),
            rs.getString("current_step"),
            rs.getString("failed_step"),
            errorCode == null ? null : TaskErrorCode.valueOf(errorCode),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
            completedAt == null ? null : completedAt.toInstant()
        );
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
