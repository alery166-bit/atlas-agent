package com.atlas.enterprise.risk.api;

import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.risk.RiskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ConfirmedRiskEventRequest(
    @NotNull RiskType riskType,
    @NotBlank String referenceId,
    String title,
    List<String> evidenceIds
) {
    ConfirmedRiskEvent toDomain() {
        return new ConfirmedRiskEvent(riskType, referenceId, title, evidenceIds);
    }
}
