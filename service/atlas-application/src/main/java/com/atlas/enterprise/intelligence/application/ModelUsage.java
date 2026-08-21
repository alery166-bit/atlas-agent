package com.atlas.enterprise.intelligence.application;

public record ModelUsage(
    int callCount,
    int promptTokens,
    int completionTokens,
    int totalTokens
) {
    public static final ModelUsage NONE = new ModelUsage(0, 0, 0, 0);

    public ModelUsage {
        if (callCount < 0 || promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Model usage values cannot be negative");
        }
    }

    public ModelUsage plus(ModelUsage other) {
        if (other == null) return this;
        return new ModelUsage(
            callCount + other.callCount,
            promptTokens + other.promptTokens,
            completionTokens + other.completionTokens,
            totalTokens + other.totalTokens
        );
    }
}
