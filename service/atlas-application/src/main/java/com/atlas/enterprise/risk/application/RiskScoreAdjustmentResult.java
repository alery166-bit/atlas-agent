package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskScoreSnapshot;

public record RiskScoreAdjustmentResult(
    RiskScoreSnapshot score,
    OperatorDecision decision,
    boolean floorOverrideWarning
) {
}
