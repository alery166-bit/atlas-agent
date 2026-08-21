package com.atlas.enterprise.agent.application;

import com.atlas.enterprise.task.application.TaskWorkspaceView;
import java.util.List;
import java.util.UUID;

public record AgentMessageView(
    UUID conversationId,
    UUID messageId,
    AgentResponseType type,
    String assistantMessage,
    AgentIntent parsedIntent,
    String companyQuery,
    TaskWorkspaceView workspace,
    List<RequiredInput> requiredInputs,
    List<SuggestedAction> suggestedActions
) {
    public AgentMessageView {
        requiredInputs = List.copyOf(requiredInputs);
        suggestedActions = List.copyOf(suggestedActions);
    }

    public record RequiredInput(
        String code,
        String label,
        boolean required
    ) {
    }

    public record SuggestedAction(
        String code,
        String label,
        String method,
        String endpoint
    ) {
    }
}
