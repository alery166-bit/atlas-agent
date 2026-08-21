package com.atlas.enterprise.agent.port;

public record AgentIntentPrediction(
    String schemaVersion,
    String intent,
    String companyQuery,
    String taskReference,
    Double confidence
) {
}
