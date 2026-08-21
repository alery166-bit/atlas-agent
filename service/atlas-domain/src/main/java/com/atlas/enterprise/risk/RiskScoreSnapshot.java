package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskScoreSnapshot(
    UUID scoreSnapshotId,
    UUID taskId,
    UUID dataSnapshotId,
    BigDecimal legacyScore,
    BigDecimal ruleCalculatedScore,
    BigDecimal eventFloorScore,
    BigDecimal originalScore,
    BigDecimal manualScore,
    RiskLevel originalRiskLevel,
    RiskLevel manualRiskLevel,
    String ruleVersion,
    String engineVersion,
    String inputHash,
    List<RiskRuleHit> ruleHits,
    Instant calculatedAt
) {
    public RiskScoreSnapshot {
        ruleHits = ruleHits == null ? List.of() : List.copyOf(ruleHits);
    }

    public static RiskScoreSnapshot create(
        UUID scoreSnapshotId,
        UUID taskId,
        UUID dataSnapshotId,
        RiskScoreResult result
    ) {
        return new RiskScoreSnapshot(
            scoreSnapshotId,
            taskId,
            dataSnapshotId,
            result.legacyScore(),
            result.ruleCalculatedScore(),
            result.eventFloorScore(),
            result.originalScore(),
            result.manualScore(),
            result.originalRiskLevel(),
            result.manualRiskLevel(),
            result.ruleVersion(),
            result.engineVersion(),
            result.inputHash(),
            result.ruleHits(),
            result.calculatedAt()
        );
    }

    public RiskScoreSnapshot adjustManualScore(
        UUID adjustedSnapshotId,
        BigDecimal score,
        Instant adjustedAt
    ) {
        if (adjustedSnapshotId == null) {
            throw new IllegalArgumentException("adjustedSnapshotId is required");
        }
        if (adjustedAt == null) {
            throw new IllegalArgumentException("adjustedAt is required");
        }
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException("manualScore must be in [0,10]");
        }
        return new RiskScoreSnapshot(
            adjustedSnapshotId,
            taskId,
            dataSnapshotId,
            legacyScore,
            ruleCalculatedScore,
            eventFloorScore,
            originalScore,
            score,
            originalRiskLevel,
            RiskLevel.from(score),
            ruleVersion,
            engineVersion,
            inputHash,
            ruleHits,
            adjustedAt
        );
    }
}
