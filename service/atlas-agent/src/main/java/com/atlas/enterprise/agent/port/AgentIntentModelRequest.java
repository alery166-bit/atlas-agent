package com.atlas.enterprise.agent.port;

import java.util.List;

public record AgentIntentModelRequest(
    String schemaVersion,
    String message,
    boolean hasTaskId,
    List<String> allowedIntents
) {
    public AgentIntentModelRequest {
        allowedIntents = List.copyOf(allowedIntents);
    }
}
