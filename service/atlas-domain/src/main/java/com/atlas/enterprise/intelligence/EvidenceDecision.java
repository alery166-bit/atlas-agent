package com.atlas.enterprise.intelligence;

import java.time.Instant;
import java.util.UUID;

public record EvidenceDecision(
    UUID decisionId,
    UUID taskId,
    UUID evidenceId,
    EvidenceVerificationStatus decision,
    String reason,
    String operatorId,
    Instant decidedAt
) {
    public EvidenceDecision {
        if (decisionId == null || taskId == null || evidenceId == null) {
            throw new IllegalArgumentException("decision identifiers are required");
        }
        if (decision == null || decision == EvidenceVerificationStatus.UNVERIFIED) {
            throw new IllegalArgumentException("decision must be CONFIRMED or REJECTED");
        }
        if (reason == null || reason.isBlank() || operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("reason and operatorId are required");
        }
        if (decidedAt == null) {
            throw new IllegalArgumentException("decidedAt is required");
        }
    }
}
