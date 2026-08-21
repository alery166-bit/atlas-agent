package com.atlas.enterprise.intelligence.application;

public record AutomatedEvidenceDecisionSummary(
    int confirmedCount,
    int rejectedCount,
    int manualReviewCount
) {
    public boolean operatorDecisionRequired() {
        return manualReviewCount > 0;
    }
}
