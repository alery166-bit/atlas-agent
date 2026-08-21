package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RiskScoreResult(
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
    public RiskScoreResult {
        ruleHits = ruleHits == null ? List.of() : List.copyOf(ruleHits);
    }
}
