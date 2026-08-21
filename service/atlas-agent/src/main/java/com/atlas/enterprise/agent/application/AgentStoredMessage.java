package com.atlas.enterprise.agent.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentStoredMessage(
    UUID messageId,
    UUID conversationId,
    AgentMessageRole role,
    String content,
    AgentResponseType responseType,
    AgentIntent parsedIntent,
    String companyQuery,
    UUID taskId,
    String idempotencyKey,
    List<AgentMessageView.RequiredInput> requiredInputs,
    List<AgentMessageView.SuggestedAction> suggestedActions,
    Instant createdAt
) {
    public AgentStoredMessage {
        requiredInputs = requiredInputs == null
            ? List.of()
            : List.copyOf(requiredInputs);
        suggestedActions = suggestedActions == null
            ? List.of()
            : List.copyOf(suggestedActions);
    }
}
