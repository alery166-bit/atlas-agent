package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.util.List;

public record RiskRuleHit(
    String ruleCode,
    String ruleName,
    RiskType riskType,
    BigDecimal score,
    String scoreRole,
    List<String> references
) {
    public RiskRuleHit {
        references = references == null ? List.of() : List.copyOf(references);
    }
}
