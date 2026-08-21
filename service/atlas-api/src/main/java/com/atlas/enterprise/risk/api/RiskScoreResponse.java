package com.atlas.enterprise.risk.api;

import com.atlas.enterprise.risk.RiskLevel;
import com.atlas.enterprise.risk.RiskRuleHit;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskScoreResponse(
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
    static RiskScoreResponse from(RiskScoreSnapshot score) {
        return new RiskScoreResponse(
            score.scoreSnapshotId(),
            score.taskId(),
            score.dataSnapshotId(),
            score.legacyScore(),
            score.ruleCalculatedScore(),
            score.eventFloorScore(),
            score.originalScore(),
            score.manualScore(),
            score.originalRiskLevel(),
            score.manualRiskLevel(),
            score.ruleVersion(),
            score.engineVersion(),
            score.inputHash(),
            score.ruleHits(),
            score.calculatedAt()
        );
    }
}
