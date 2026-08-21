package com.atlas.enterprise.intelligence.storage;

import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewJob;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticReviewJobRepository;
import com.atlas.enterprise.risk.RiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvidenceSemanticReviewJobRepository
    implements EvidenceSemanticReviewJobRepository {

    private static final String SELECT = """
        SELECT review_job_id, task_id, status, total_count, processed_count,
               reviewed_count, failed_count, provider, model,
               model_call_count, prompt_token_count, completion_token_count,
               total_token_count,
               model_suggested_score, model_suggested_risk_level,
               model_score_evidence_json, advisory_rule_version, error_message,
               cancel_requested, created_at, started_at, finished_at, updated_at
          FROM evidence_semantic_review_job
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEvidenceSemanticReviewJobRepository(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public EvidenceSemanticReviewJob save(EvidenceSemanticReviewJob job) {
        jdbc.update("""
            INSERT INTO evidence_semantic_review_job (
                review_job_id, task_id, status, total_count, processed_count,
                reviewed_count, failed_count, provider, model,
                model_call_count, prompt_token_count, completion_token_count,
                total_token_count, error_message,
                model_suggested_score, model_suggested_risk_level,
                model_score_evidence_json, advisory_rule_version,
                cancel_requested, active_task_id, created_at, started_at,
                finished_at, updated_at
            ) VALUES (
                :jobId, :taskId, :status, :total, :processed,
                :reviewed, :failed, :provider, :model,
                :modelCallCount, :promptTokenCount, :completionTokenCount,
                :totalTokenCount, :error,
                :modelSuggestedScore, :modelSuggestedRiskLevel,
                :modelScoreEvidence, :advisoryRuleVersion,
                :cancelRequested, :activeTaskId, :createdAt, :startedAt,
                :finishedAt, :updatedAt
            )
            """, parameters(job));
        return job;
    }

    @Override
    public EvidenceSemanticReviewJob update(EvidenceSemanticReviewJob job) {
        int updated = jdbc.update("""
            UPDATE evidence_semantic_review_job
               SET status = :status,
                   total_count = :total,
                   processed_count = :processed,
                   reviewed_count = :reviewed,
                   failed_count = :failed,
                   provider = :provider,
                   model = :model,
                   model_call_count = :modelCallCount,
                   prompt_token_count = :promptTokenCount,
                   completion_token_count = :completionTokenCount,
                   total_token_count = :totalTokenCount,
                   model_suggested_score = :modelSuggestedScore,
                   model_suggested_risk_level = :modelSuggestedRiskLevel,
                   model_score_evidence_json = :modelScoreEvidence,
                   advisory_rule_version = :advisoryRuleVersion,
                   error_message = :error,
                   cancel_requested = :cancelRequested,
                   active_task_id = :activeTaskId,
                   started_at = :startedAt,
                   finished_at = :finishedAt,
                   updated_at = :updatedAt
             WHERE review_job_id = :jobId
            """, parameters(job));
        if (updated != 1) {
            throw new IllegalStateException("Semantic review job not found: " + job.reviewJobId());
        }
        return job;
    }

    @Override
    public Optional<EvidenceSemanticReviewJob> findById(UUID reviewJobId) {
        return query(SELECT + " WHERE review_job_id = :jobId", Map.of("jobId", reviewJobId));
    }

    @Override
    public Optional<EvidenceSemanticReviewJob> findLatestByTaskId(UUID taskId) {
        return query(SELECT + " WHERE task_id = :taskId ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY",
            Map.of("taskId", taskId));
    }

    @Override
    public Optional<EvidenceSemanticReviewJob> findActiveByTaskId(UUID taskId) {
        return query(SELECT + " WHERE active_task_id = :taskId", Map.of("taskId", taskId));
    }

    @Override
    public int failInterruptedJobs(Instant failedAt, String errorMessage) {
        return jdbc.update("""
            UPDATE evidence_semantic_review_job
               SET status = 'FAILED', active_task_id = NULL,
                   error_message = :error, finished_at = :failedAt,
                   updated_at = :failedAt
             WHERE active_task_id IS NOT NULL
            """, Map.of(
                "error", errorMessage,
                "failedAt", Timestamp.from(failedAt)
            ));
    }

    private Optional<EvidenceSemanticReviewJob> query(String sql, Map<String, ?> parameters) {
        List<EvidenceSemanticReviewJob> jobs = jdbc.query(sql, parameters, this::map);
        return jobs.stream().findFirst();
    }

    private MapSqlParameterSource parameters(EvidenceSemanticReviewJob job) {
        return new MapSqlParameterSource()
            .addValue("jobId", job.reviewJobId())
            .addValue("taskId", job.taskId())
            .addValue("status", job.status().name())
            .addValue("total", job.totalCount())
            .addValue("processed", job.processedCount())
            .addValue("reviewed", job.reviewedCount())
            .addValue("failed", job.failedCount())
            .addValue("provider", job.provider())
            .addValue("model", job.model())
            .addValue("modelCallCount", job.modelCallCount())
            .addValue("promptTokenCount", job.promptTokenCount())
            .addValue("completionTokenCount", job.completionTokenCount())
            .addValue("totalTokenCount", job.totalTokenCount())
            .addValue("modelSuggestedScore", job.modelSuggestedScore())
            .addValue("modelSuggestedRiskLevel", job.modelSuggestedRiskLevel() == null
                ? null : job.modelSuggestedRiskLevel().name())
            .addValue("modelScoreEvidence", json(job.modelScoreEvidenceIds()))
            .addValue("advisoryRuleVersion", job.advisoryRuleVersion())
            .addValue("error", job.errorMessage())
            .addValue("cancelRequested", job.cancelRequested())
            .addValue("activeTaskId", job.active() ? job.taskId() : null)
            .addValue("createdAt", Timestamp.from(job.createdAt()))
            .addValue("startedAt", timestamp(job.startedAt()))
            .addValue("finishedAt", timestamp(job.finishedAt()))
            .addValue("updatedAt", Timestamp.from(job.updatedAt()));
    }

    private EvidenceSemanticReviewJob map(ResultSet rs, int rowNumber) throws SQLException {
        return new EvidenceSemanticReviewJob(
            rs.getObject("review_job_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            EvidenceSemanticReviewJob.Status.valueOf(rs.getString("status")),
            rs.getInt("total_count"),
            rs.getInt("processed_count"),
            rs.getInt("reviewed_count"),
            rs.getInt("failed_count"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getInt("model_call_count"),
            rs.getInt("prompt_token_count"),
            rs.getInt("completion_token_count"),
            rs.getInt("total_token_count"),
            rs.getBigDecimal("model_suggested_score"),
            riskLevel(rs.getString("model_suggested_risk_level")),
            evidenceIds(rs.getString("model_score_evidence_json")),
            rs.getString("advisory_rule_version"),
            rs.getString("error_message"),
            rs.getBoolean("cancel_requested"),
            rs.getTimestamp("created_at").toInstant(),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static RiskLevel riskLevel(String value) {
        return value == null ? null : RiskLevel.valueOf(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize model score evidence", exception);
        }
    }

    private List<UUID> evidenceIds(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<UUID>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize model score evidence", exception);
        }
    }
}
