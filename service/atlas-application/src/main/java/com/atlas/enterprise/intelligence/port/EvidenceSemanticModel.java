package com.atlas.enterprise.intelligence.port;

import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewRequest;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewOutcome;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticSuggestion;
import java.util.List;
import java.util.UUID;

/** Semantic evidence judge used by the automatic investigation workflow. */
public interface EvidenceSemanticModel {
    boolean available(UUID taskId);

    String provider(UUID taskId);

    String model(UUID taskId);

    /**
     * Automatic decisions remain policy-gated. Implementations may disable
     * them per published model configuration without disabling semantic review.
     */
    default boolean automaticDecisionEnabled(UUID taskId) {
        return true;
    }

    /** Minimum confidence for an automatic confirm or reject decision. */
    default double automaticDecisionThreshold(UUID taskId) {
        return 0.90D;
    }

    EvidenceSemanticReviewOutcome review(EvidenceSemanticReviewRequest request);
}
