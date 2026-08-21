package com.atlas.enterprise.risk.api;

import com.atlas.enterprise.risk.RiskAdjustmentReason;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AdjustRiskScoreRequest(
    @NotNull @DecimalMin("0") @DecimalMax("10") BigDecimal manualScore,
    @NotNull RiskAdjustmentReason reasonCode,
    @NotBlank String reasonText
) {
}
