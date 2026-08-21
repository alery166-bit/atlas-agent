package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.util.List;

/** A versioned risk label with its provenance at assessment time. */
public record RiskAssessmentLabel(
    String labelCode,
    String labelName,
    RiskType riskType,
    String sourceType,
    BigDecimal scoreContribution,
    BigDecimal confidence,
    List<String> references
) {
    public RiskAssessmentLabel {
        if (labelName == null || labelName.isBlank()) {
            throw new IllegalArgumentException("labelName is required");
        }
        riskType = riskType == null ? RiskType.OTHER : riskType;
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType is required");
        }
        if (confidence != null
            && (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        references = references == null ? List.of() : List.copyOf(references);
    }
}
