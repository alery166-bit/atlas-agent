package com.atlas.enterprise.risk;

import java.util.List;

public record ConfirmedRiskEvent(
    RiskType riskType,
    String referenceId,
    String title,
    List<String> evidenceIds
) {
    public ConfirmedRiskEvent {
        if (riskType == null) {
            throw new IllegalArgumentException("riskType is required");
        }
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("referenceId is required");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
