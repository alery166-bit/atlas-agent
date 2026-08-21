package com.atlas.enterprise.risk.api;

import com.atlas.enterprise.risk.OperatorDecision;

public record RiskScoreAdjustmentResponse(
    RiskScoreResponse score,
    OperatorDecision decision,
    boolean floorOverrideWarning
) {
}
