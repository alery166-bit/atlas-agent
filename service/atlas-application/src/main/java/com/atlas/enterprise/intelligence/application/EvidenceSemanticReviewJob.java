package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EvidenceSemanticReviewJob(
    UUID reviewJobId,
    UUID taskId,
    Status status,
    int totalCount,
    int processedCount,
    int reviewedCount,
    int failedCount,
    String provider,
    String model,
    int modelCallCount,
    int promptTokenCount,
    int completionTokenCount,
    int totalTokenCount,
    BigDecimal modelSuggestedScore,
    RiskLevel modelSuggestedRiskLevel,
    List<UUID> modelScoreEvidenceIds,
    String advisoryRuleVersion,
    String errorMessage,
    boolean cancelRequested,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant updatedAt
) {
    public EvidenceSemanticReviewJob {
        if (reviewJobId == null || taskId == null || status == null
            || totalCount < 0 || processedCount < 0 || reviewedCount < 0
            || failedCount < 0 || modelCallCount < 0 || promptTokenCount < 0
            || completionTokenCount < 0 || totalTokenCount < 0
            || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Invalid semantic review job");
        }
        if (modelSuggestedScore != null
            && (modelSuggestedScore.compareTo(BigDecimal.ZERO) < 0
                || modelSuggestedScore.compareTo(BigDecimal.TEN) > 0)) {
            throw new IllegalArgumentException("modelSuggestedScore must be in [0,10]");
        }
        if ((modelSuggestedScore == null) != (modelSuggestedRiskLevel == null)) {
            throw new IllegalArgumentException(
                "modelSuggestedScore and modelSuggestedRiskLevel must be set together"
            );
        }
        modelScoreEvidenceIds = modelScoreEvidenceIds == null
            ? List.of()
            : List.copyOf(modelScoreEvidenceIds);
    }

    public boolean active() {
        return status == Status.QUEUED
            || status == Status.RUNNING
            || status == Status.CANCEL_REQUESTED;
    }

    public enum Status {
        QUEUED,
        RUNNING,
        CANCEL_REQUESTED,
        SUCCEEDED,
        PARTIAL_FAILED,
        FAILED,
        CANCELLED
    }
}
