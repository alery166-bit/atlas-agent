package com.atlas.enterprise.agent.api;

import com.atlas.enterprise.agent.application.AgentIntent;
import com.atlas.enterprise.agent.application.AgentMessageView;
import com.atlas.enterprise.agent.application.AgentResponseType;
import com.atlas.enterprise.task.api.TaskWorkspaceResponse;
import java.util.List;
import java.util.UUID;

public record AgentMessageResponse(
    UUID conversationId,
    UUID messageId,
    AgentResponseType type,
    String assistantMessage,
    AgentIntent parsedIntent,
    String companyQuery,
    TaskWorkspaceResponse workspace,
    List<AgentMessageView.RequiredInput> requiredInputs,
    List<AgentMessageView.SuggestedAction> suggestedActions
) {
    public static AgentMessageResponse from(AgentMessageView view) {
        return new AgentMessageResponse(
            view.conversationId(),
            view.messageId(),
            view.type(),
            view.assistantMessage(),
            view.parsedIntent(),
            view.companyQuery(),
            view.workspace() == null
                ? null
                : TaskWorkspaceResponse.from(view.workspace()),
            view.requiredInputs(),
            view.suggestedActions()
        );
    }
}
