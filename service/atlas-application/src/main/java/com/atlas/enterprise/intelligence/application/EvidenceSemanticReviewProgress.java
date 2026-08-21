package com.atlas.enterprise.intelligence.application;

@FunctionalInterface
public interface EvidenceSemanticReviewProgress {
    void update(
        int totalCount,
        int processedCount,
        int reviewedCount,
        int failedCount,
        String provider,
        String model
    );

    static EvidenceSemanticReviewProgress noop() {
        return (total, processed, reviewed, failed, provider, model) -> { };
    }
}
