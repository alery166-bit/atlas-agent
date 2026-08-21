package com.atlas.enterprise.risk;

import com.atlas.enterprise.company.CompanyFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskScoreRequest(
    UUID taskId,
    UUID dataSnapshotId,
    CompanyFacts companyFacts,
    List<ConfirmedRiskEvent> confirmedRiskEvents,
    BigDecimal legacyScore,
    LegacyRiskFeatures legacyFeatures,
    RiskScoringPolicy scoringPolicy,
    String ruleVersion,
    Instant calculatedAt
) {
    public RiskScoreRequest {
        if (taskId == null || dataSnapshotId == null || companyFacts == null) {
            throw new IllegalArgumentException("taskId, dataSnapshotId and companyFacts are required");
        }
        confirmedRiskEvents = confirmedRiskEvents == null ? List.of() : List.copyOf(confirmedRiskEvents);
        legacyFeatures = legacyFeatures == null ? LegacyRiskFeatures.incomplete() : legacyFeatures;
        scoringPolicy = scoringPolicy == null ? RiskScoringPolicy.defaultPolicy() : scoringPolicy;
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("ruleVersion is required");
        }
        if (calculatedAt == null) {
            throw new IllegalArgumentException("calculatedAt is required");
        }
        if (legacyScore != null
            && (legacyScore.compareTo(BigDecimal.ZERO) < 0
                || legacyScore.compareTo(BigDecimal.TEN) > 0)) {
            throw new IllegalArgumentException("legacyScore must be in [0,10]");
        }
    }

    public RiskScoreRequest(
        UUID taskId,
        UUID dataSnapshotId,
        CompanyFacts companyFacts,
        List<ConfirmedRiskEvent> confirmedRiskEvents,
        BigDecimal legacyScore,
        String ruleVersion,
        Instant calculatedAt
    ) {
        this(
            taskId,
            dataSnapshotId,
            companyFacts,
            confirmedRiskEvents,
            legacyScore,
            LegacyRiskFeatures.incomplete(),
            RiskScoringPolicy.defaultPolicy(),
            ruleVersion,
            calculatedAt
        );
    }

    public RiskScoreRequest(
        UUID taskId,
        UUID dataSnapshotId,
        CompanyFacts companyFacts,
        List<ConfirmedRiskEvent> confirmedRiskEvents,
        BigDecimal legacyScore,
        LegacyRiskFeatures legacyFeatures,
        String ruleVersion,
        Instant calculatedAt
    ) {
        this(
            taskId,
            dataSnapshotId,
            companyFacts,
            confirmedRiskEvents,
            legacyScore,
            legacyFeatures,
            RiskScoringPolicy.defaultPolicy(),
            ruleVersion,
            calculatedAt
        );
    }
}
