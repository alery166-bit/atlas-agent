package com.atlas.enterprise.intelligence.application;

import java.util.List;

public record EvidenceSemanticReviewOutcome(
    List<EvidenceSemanticSuggestion> suggestions,
    ModelUsage usage
) {
    public EvidenceSemanticReviewOutcome {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        usage = usage == null ? ModelUsage.NONE : usage;
    }
}
