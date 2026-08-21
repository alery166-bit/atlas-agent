package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.intelligence.PublicEvidence;
import java.util.List;
import java.util.UUID;

public record EvidenceSemanticReviewRequest(
    UUID taskId,
    String companyName,
    List<String> confirmedAliases,
    List<PublicEvidence> evidence
) {
    public EvidenceSemanticReviewRequest {
        if (taskId == null || companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("taskId and companyName are required");
        }
        confirmedAliases = confirmedAliases == null ? List.of() : List.copyOf(confirmedAliases);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
