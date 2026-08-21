package com.atlas.enterprise.intelligence.api;

import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EvidenceDecisionRequest(
    @NotNull EvidenceVerificationStatus decision,
    @NotBlank String reason
) {}
