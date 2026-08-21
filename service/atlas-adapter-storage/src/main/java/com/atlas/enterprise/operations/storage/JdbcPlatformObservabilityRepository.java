package com.atlas.enterprise.operations.storage;

import com.atlas.enterprise.operations.port.PlatformObservabilityPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPlatformObservabilityRepository implements PlatformObservabilityPort {
    private static final Duration ACTIVE_THRESHOLD = Duration.ofMinutes(15);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(ZoneOffset.UTC);
    private final JdbcClient jdbc;

    public JdbcPlatformObservabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OperationsSnapshot observe(Instant from, Instant to, int failureLimit) {
        List<TaskRow> tasks = jdbc.sql("""
                SELECT t.task_id, t.task_no, t.company_query, c.canonical_name,
                       t.status, t.current_step, t.failed_step, t.error_code, t.created_at,
                       t.updated_at, t.completed_at,
                       (SELECT j.updated_at FROM evidence_semantic_review_job j
                         WHERE j.task_id = t.task_id
                         ORDER BY j.created_at DESC FETCH FIRST 1 ROW ONLY) AS model_updated_at,
                       (SELECT j.status FROM evidence_semantic_review_job j
                         WHERE j.task_id = t.task_id
                         ORDER BY j.created_at DESC FETCH FIRST 1 ROW ONLY) AS latest_model_status,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM evidence e
                            WHERE e.task_id = t.task_id
                              AND e.verification_status = 'UNVERIFIED'
                       ) THEN TRUE ELSE FALSE END AS has_unverified_evidence,
                       (SELECT COUNT(*) FROM public_search_batch s
                         WHERE s.task_id = t.task_id) AS search_calls,
                       COALESCE((SELECT SUM(j.model_call_count)
                         FROM evidence_semantic_review_job j
                        WHERE j.task_id = t.task_id), 0) AS model_calls,
                       COALESCE((SELECT SUM(j.prompt_token_count)
                         FROM evidence_semantic_review_job j
                        WHERE j.task_id = t.task_id), 0) AS prompt_tokens,
                       COALESCE((SELECT SUM(j.completion_token_count)
                         FROM evidence_semantic_review_job j
                        WHERE j.task_id = t.task_id), 0) AS completion_tokens,
                       COALESCE((SELECT SUM(j.total_token_count)
                         FROM evidence_semantic_review_job j
                        WHERE j.task_id = t.task_id), 0) AS total_tokens,
                       (SELECT r.status FROM report_version r
                         WHERE r.task_id = t.task_id
                         ORDER BY r.report_version_no DESC FETCH FIRST 1 ROW ONLY) AS report_status
                  FROM investigation_task t
                  LEFT JOIN atlas_company c ON c.atlas_company_id = t.atlas_company_id
                 WHERE t.created_at >= :from AND t.created_at < :to
                 ORDER BY t.created_at DESC
                """)
            .param("from", time(from)).param("to", time(to))
            .query(this::task).list();

        long completed = tasks.stream().filter(row -> "COMPLETED".equals(row.status())).count();
        Instant activeCutoff = to.minus(ACTIVE_THRESHOLD);
        long failed = tasks.stream().filter(row -> isFailure(row.status())).count();
        long waiting = tasks.stream().filter(JdbcPlatformObservabilityRepository::isWaiting)
            .count();
        long active = tasks.stream().filter(JdbcPlatformObservabilityRepository::isRunning)
            .filter(row -> !row.updatedAt().isBefore(activeCutoff)).count();
        List<StalledTask> stalled = tasks.stream()
            .filter(JdbcPlatformObservabilityRepository::isRunning)
            .filter(row -> row.updatedAt().isBefore(activeCutoff))
            .map(row -> new StalledTask(
                row.taskId(), row.taskNo(), row.enterpriseName(), row.status(), row.currentStep(),
                row.createdAt(), row.updatedAt(),
                Math.max(1, Duration.between(row.updatedAt(), to).toMinutes())
            )).toList();
        List<Long> durations = tasks.stream()
            .filter(row -> row.completedAt() != null)
            .map(row -> Duration.between(row.createdAt(), row.completedAt()).toMillis())
            .toList();
        Long averageDuration = durations.isEmpty() ? null
            : Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0));
        long searches = tasks.stream().mapToLong(TaskRow::searchCalls).sum();
        long modelCalls = tasks.stream().mapToLong(TaskRow::modelCalls).sum();
        long promptTokens = tasks.stream().mapToLong(TaskRow::promptTokens).sum();
        long completionTokens = tasks.stream().mapToLong(TaskRow::completionTokens).sum();
        long totalTokens = tasks.stream().mapToLong(TaskRow::totalTokens).sum();
        long generatedReports = tasks.stream()
            .filter(row -> "GENERATED".equals(row.reportStatus())).count();
        long failedReports = tasks.stream()
            .filter(row -> "FAILED".equals(row.reportStatus())).count();

        Map<String, long[]> daily = new LinkedHashMap<>();
        tasks.stream().sorted(Comparator.comparing(TaskRow::createdAt)).forEach(row -> {
            long[] values = daily.computeIfAbsent(DAY.format(row.createdAt()), ignored -> new long[3]);
            values[0]++;
            if ("COMPLETED".equals(row.status())) values[1]++;
            if (isFailure(row.status())) values[2]++;
        });
        List<ThroughputPoint> throughput = daily.entrySet().stream()
            .map(entry -> new ThroughputPoint(
                entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]
            )).toList();
        List<FailedTask> failures = tasks.stream().filter(row -> isFailure(row.status()))
            .limit(failureLimit)
            .map(row -> new FailedTask(
                row.taskId(), row.taskNo(), row.enterpriseName(), row.status(), row.failedStep(),
                row.errorCode(), row.createdAt(), row.updatedAt(), row.searchCalls(), row.modelCalls(),
                row.reportStatus(), retryable(row.errorCode())
            )).toList();
        return new OperationsSnapshot(
            from, to, tasks.size(), completed, active, waiting, stalled.size(), failed,
            ACTIVE_THRESHOLD.toMinutes(), averageDuration, searches,
            modelCalls, "RECORDED", promptTokens, completionTokens, totalTokens,
            generatedReports, failedReports, throughput, stalled, failures
        );
    }

    @Override
    public List<AuditEntry> audit(AuditFilter filter) {
        List<AuditEntry> entries = new ArrayList<>();
        entries.addAll(auditEvents(filter.from(), filter.to()));
        entries.addAll(operatorDecisions(filter.from(), filter.to()));
        entries.addAll(evidenceDecisions(filter.from(), filter.to()));
        entries.addAll(subjectDataConflictResolutions(filter.from(), filter.to()));
        entries.addAll(confirmations(filter.from(), filter.to()));
        entries.addAll(reportEvents(filter.from(), filter.to()));
        entries.addAll(stepEvents(filter.from(), filter.to()));
        entries.addAll(configurationReleases(filter.from(), filter.to()));

        Predicate<AuditEntry> matches = entry ->
            (filter.taskId() == null || filter.taskId().equals(entry.taskId()))
            && contains(entry.enterpriseName(), filter.enterprise())
            && contains(entry.operatorId(), filter.operatorId())
            && (filter.eventType() == null
                || entry.eventType().equalsIgnoreCase(filter.eventType())
                || contains(entry.action(), filter.eventType()));
        return entries.stream().filter(matches)
            .sorted(Comparator.comparing(AuditEntry::occurredAt).reversed())
            .limit(filter.limit()).toList();
    }

    @Override
    public ConfigurationChange configurationChange(UUID releaseId) {
        return jdbc.sql("""
                SELECT r.release_id, r.config_id, d.config_key, d.display_name,
                       r.environment, r.action, r.from_version_id,
                       fv.version_no AS from_version_no, fv.value_json AS before_json,
                       r.to_version_id, tv.version_no AS to_version_no,
                       tv.value_json AS after_json, r.operator_id, r.occurred_at
                  FROM configuration_release r
                  JOIN configuration_definition d ON d.config_id = r.config_id
                  LEFT JOIN configuration_version fv ON fv.version_id = r.from_version_id
                  JOIN configuration_version tv ON tv.version_id = r.to_version_id
                 WHERE r.release_id = :releaseId
                """)
            .param("releaseId", releaseId)
            .query(this::mapConfigurationChange)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("configuration release not found"));
    }

    @Override
    public void recordRetry(UUID taskId, String operatorId, String traceId, Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO audit_event (
                    task_id, trace_id, actor_type, actor_id, action,
                    target_type, target_id, payload_digest, occurred_at
                ) VALUES (
                    :taskId, :traceId, 'OPERATOR', :operatorId, 'task.retry',
                    'INVESTIGATION_TASK', :targetId, NULL, :occurredAt
                )
                """)
            .param("taskId", taskId).param("traceId", traceId)
            .param("operatorId", operatorId).param("targetId", taskId.toString())
            .param("occurredAt", time(occurredAt)).update();
    }

    private List<AuditEntry> auditEvents(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT CAST(a.audit_id AS VARCHAR) AS event_id, a.action, a.task_id,
                       t.task_no, COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       a.actor_id, a.actor_type, a.target_type, a.target_id,
                       a.payload_digest, a.trace_id, a.occurred_at
                  FROM audit_event a
                  LEFT JOIN investigation_task t ON t.task_id = a.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id = t.atlas_company_id
                 WHERE a.occurred_at >= :from AND a.occurred_at < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("event_id"), "AUDIT_EVENT", rs.getString("action"),
                uuid(rs, "task_id"), rs.getString("task_no"), rs.getString("enterprise_name"),
                rs.getString("actor_id"), rs.getString("actor_type"), rs.getString("target_type"),
                rs.getString("target_id"), null, null, rs.getString("payload_digest"),
                rs.getString("trace_id"), instant(rs, "occurred_at")
            )).list();
    }

    private List<AuditEntry> operatorDecisions(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT d.decision_id, d.task_id, t.task_no,
                       COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       d.operator_id, d.target_type, d.target_id, d.decision_type,
                       d.before_json, d.after_json, d.reason_code, d.reason_text, d.created_at
                  FROM operator_decision d JOIN investigation_task t ON t.task_id=d.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id=t.atlas_company_id
                 WHERE d.created_at >= :from AND d.created_at < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("decision_id"), "OPERATOR_DECISION", rs.getString("decision_type"),
                uuid(rs, "task_id"), rs.getString("task_no"), rs.getString("enterprise_name"),
                rs.getString("operator_id"), "OPERATOR", rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("before_json"), rs.getString("after_json"),
                rs.getString("reason_code") + ": " + rs.getString("reason_text"), null,
                instant(rs, "created_at")
            )).list();
    }

    private List<AuditEntry> evidenceDecisions(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT d.decision_id, d.task_id, t.task_no,
                       COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       d.operator_id, d.evidence_id, d.decision, d.reason, d.decided_at
                  FROM evidence_decision d JOIN investigation_task t ON t.task_id=d.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id=t.atlas_company_id
                 WHERE d.decided_at >= :from AND d.decided_at < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("decision_id"), "EVIDENCE_DECISION", rs.getString("decision"),
                uuid(rs, "task_id"), rs.getString("task_no"), rs.getString("enterprise_name"),
                rs.getString("operator_id"), actorType(rs.getString("operator_id")),
                "EVIDENCE", rs.getString("evidence_id"),
                null, "{\"decision\":\"" + rs.getString("decision") + "\"}",
                rs.getString("reason"), null, instant(rs, "decided_at")
            )).list();
    }

    private List<AuditEntry> confirmations(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT o.confirmation_id, o.task_id, t.task_no,
                       COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       o.operator_id, o.review_state_hash, o.confirmed_evidence_count,
                       o.rejected_evidence_count, o.note, o.confirmed_at
                  FROM operator_confirmation o JOIN investigation_task t ON t.task_id=o.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id=t.atlas_company_id
                 WHERE o.confirmed_at >= :from AND o.confirmed_at < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("confirmation_id"), "OPERATOR_CONFIRMATION", "operator.confirmed",
                uuid(rs, "task_id"), rs.getString("task_no"), rs.getString("enterprise_name"),
                rs.getString("operator_id"), actorType(rs.getString("operator_id")),
                "REVIEW_STATE",
                rs.getString("review_state_hash"), null,
                "{\"confirmed\":" + rs.getInt("confirmed_evidence_count")
                    + ",\"rejected\":" + rs.getInt("rejected_evidence_count") + "}",
                rs.getString("note"), null, instant(rs, "confirmed_at")
            )).list();
    }

    private List<AuditEntry> subjectDataConflictResolutions(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT r.resolution_id, r.task_id, t.task_no,
                       COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       r.operator_id, r.data_snapshot_id, r.decision, r.note, r.resolved_at
                  FROM subject_data_conflict_resolution r
                  JOIN investigation_task t ON t.task_id=r.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id=t.atlas_company_id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("resolution_id"), "SUBJECT_DATA_CONFLICT_RESOLUTION",
                "subject.data.conflict.resolved", uuid(rs, "task_id"),
                rs.getString("task_no"), rs.getString("enterprise_name"),
                rs.getString("operator_id"), "OPERATOR", "DATA_SNAPSHOT",
                rs.getString("data_snapshot_id"), null,
                "{\"decision\":\"" + rs.getString("decision") + "\"}",
                rs.getString("note"), null, instant(rs, "resolved_at")
            )).list();
    }

    private List<AuditEntry> reportEvents(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT r.report_id, r.task_id, t.task_no,
                       COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       r.generated_by, r.status, r.template_version, r.report_version_no,
                       r.input_hash, r.failure_reason, COALESCE(r.generated_at,t.updated_at) occurred_at
                  FROM report_version r JOIN investigation_task t ON t.task_id=r.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id=t.atlas_company_id
                 WHERE COALESCE(r.generated_at,t.updated_at) >= :from
                   AND COALESCE(r.generated_at,t.updated_at) < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("report_id"), "REPORT_VERSION", "report." + rs.getString("status").toLowerCase(Locale.ROOT),
                uuid(rs, "task_id"), rs.getString("task_no"), rs.getString("enterprise_name"),
                rs.getString("generated_by"), "SYSTEM", "REPORT", rs.getString("report_id"),
                null, "{\"version\":" + rs.getInt("report_version_no")
                    + ",\"template\":\"" + rs.getString("template_version") + "\"}",
                rs.getString("failure_reason") == null ? rs.getString("input_hash") : rs.getString("failure_reason"),
                null, instant(rs, "occurred_at")
            )).list();
    }

    private List<AuditEntry> stepEvents(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT s.task_step_id, s.task_id, t.task_no,
                       COALESCE(c.canonical_name, t.company_query) enterprise_name,
                       s.step_name, s.status, s.attempt_no, s.error_code, s.trace_id,
                       COALESCE(s.ended_at,s.started_at) occurred_at
                  FROM task_step s JOIN investigation_task t ON t.task_id=s.task_id
                  LEFT JOIN atlas_company c ON c.atlas_company_id=t.atlas_company_id
                 WHERE COALESCE(s.ended_at,s.started_at) >= :from
                   AND COALESCE(s.ended_at,s.started_at) < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("task_step_id"), "TASK_STEP", "step." + rs.getString("status").toLowerCase(Locale.ROOT),
                uuid(rs, "task_id"), rs.getString("task_no"), rs.getString("enterprise_name"),
                null, "WORKER", "TASK_STEP", rs.getString("task_step_id"), null,
                "{\"step\":\"" + rs.getString("step_name") + "\",\"attempt\":" + rs.getInt("attempt_no") + "}",
                rs.getString("error_code"), rs.getString("trace_id"), instant(rs, "occurred_at")
            )).list();
    }

    private List<AuditEntry> configurationReleases(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT r.release_id, r.action, r.operator_id, r.config_id, d.config_key,
                       r.from_version_id, fv.value_json before_json,
                       r.to_version_id, tv.value_json after_json, r.occurred_at
                  FROM configuration_release r
                  JOIN configuration_definition d ON d.config_id=r.config_id
                  LEFT JOIN configuration_version fv ON fv.version_id=r.from_version_id
                  JOIN configuration_version tv ON tv.version_id=r.to_version_id
                 WHERE r.occurred_at >= :from AND r.occurred_at < :to
                """).param("from", time(from)).param("to", time(to))
            .query((rs, row) -> new AuditEntry(
                rs.getString("release_id"), "CONFIGURATION_RELEASE",
                "configuration." + rs.getString("action").toLowerCase(Locale.ROOT),
                null, null, null, rs.getString("operator_id"), "OPERATOR",
                "CONFIGURATION", rs.getString("config_id"), rs.getString("before_json"),
                rs.getString("after_json"), rs.getString("config_key"),
                rs.getString("release_id"), instant(rs, "occurred_at")
            )).list();
    }

    private TaskRow task(ResultSet rs, int row) throws SQLException {
        String canonical = rs.getString("canonical_name");
        return new TaskRow(
            uuid(rs, "task_id"), rs.getString("task_no"),
            canonical == null ? rs.getString("company_query") : canonical,
            rs.getString("status"), rs.getString("current_step"),
            rs.getString("failed_step"), rs.getString("error_code"),
            instant(rs, "created_at"), latestActivity(rs),
            instantNullable(rs, "completed_at"), rs.getLong("search_calls"),
            rs.getLong("model_calls"), rs.getLong("prompt_tokens"),
            rs.getLong("completion_tokens"), rs.getLong("total_tokens"),
            rs.getString("report_status"), rs.getString("latest_model_status"),
            rs.getBoolean("has_unverified_evidence")
        );
    }

    private static Instant latestActivity(ResultSet rs) throws SQLException {
        Instant taskUpdatedAt = instant(rs, "updated_at");
        Instant modelUpdatedAt = instantNullable(rs, "model_updated_at");
        return modelUpdatedAt != null && modelUpdatedAt.isAfter(taskUpdatedAt)
            ? modelUpdatedAt
            : taskUpdatedAt;
    }

    private ConfigurationChange mapConfigurationChange(ResultSet rs, int row) throws SQLException {
        Object fromVersion = rs.getObject("from_version_id");
        Object fromNo = rs.getObject("from_version_no");
        return new ConfigurationChange(
            uuid(rs, "release_id"), uuid(rs, "config_id"), rs.getString("config_key"),
            rs.getString("display_name"), rs.getString("environment"), rs.getString("action"),
            fromVersion == null ? null : UUID.fromString(fromVersion.toString()),
            fromNo == null ? null : ((Number) fromNo).intValue(), rs.getString("before_json"),
            uuid(rs, "to_version_id"), rs.getInt("to_version_no"), rs.getString("after_json"),
            rs.getString("operator_id"), instant(rs, "occurred_at")
        );
    }

    private static boolean contains(String value, String filter) {
        return filter == null || (value != null
            && value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)));
    }

    private static String actorType(String operatorId) {
        return operatorId != null && operatorId.startsWith("atlas-agent:")
            ? "AGENT"
            : "OPERATOR";
    }

    private static boolean isFailure(String status) {
        return status != null && status.endsWith("_FAILED");
    }

    private static boolean isWaitingStatus(String status) {
        return "WAITING_SUBJECT_CONFIRMATION".equals(status)
            || "WAITING_SUBJECT_DATA_REVIEW".equals(status)
            || "WAITING_OPERATOR_CONFIRMATION".equals(status);
    }

    private static boolean isWaiting(TaskRow row) {
        if (isWaitingStatus(row.status())) return true;
        return "CALCULATING_RISK".equals(row.status())
            && row.hasUnverifiedEvidence()
            && (row.latestModelStatus() == null
                || !List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED")
                    .contains(row.latestModelStatus()));
    }

    private static boolean isRunning(TaskRow row) {
        return row.status() != null
            && !isFailure(row.status())
            && !isWaiting(row)
            && !"COMPLETED".equals(row.status())
            && !"CANCELLED".equals(row.status());
    }

    private static boolean retryable(String errorCode) {
        return errorCode != null && !List.of(
            "SUBJECT_NOT_FOUND", "SUBJECT_AMBIGUOUS", "PREVIOUS_REPORT_REQUIRED",
            "PREVIOUS_REPORT_UNSUPPORTED",
            "OPERATOR_CONFIRMATION_REQUIRED", "REPORT_TEMPLATE_INVALID",
            "TASK_STATE_CONFLICT"
        ).contains(errorCode);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant instantNullable(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record TaskRow(
        UUID taskId, String taskNo, String enterpriseName, String status,
        String currentStep, String failedStep, String errorCode, Instant createdAt, Instant updatedAt,
        Instant completedAt, long searchCalls, long modelCalls, long promptTokens,
        long completionTokens, long totalTokens, String reportStatus,
        String latestModelStatus, boolean hasUnverifiedEvidence
    ) {
    }
}
