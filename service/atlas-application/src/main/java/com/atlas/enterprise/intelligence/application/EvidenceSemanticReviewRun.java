package com.atlas.enterprise.intelligence.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EvidenceSemanticReviewRun(
    UUID taskId,
    String provider,
    String model,
    int reviewedCount,
    int failedCount,
    List<EvidenceSemanticSuggestion> suggestions,
    ModelUsage usage,
    Instant reviewedAt,
    boolean operatorDecisionRequired
) {
    public EvidenceSemanticReviewRun {
        suggestions = List.copyOf(suggestions);
        usage = usage == null ? ModelUsage.NONE : usage;
    }
}
