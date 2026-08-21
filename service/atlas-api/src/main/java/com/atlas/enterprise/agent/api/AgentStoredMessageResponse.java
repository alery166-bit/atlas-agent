package com.atlas.enterprise.agent.api;

import com.atlas.enterprise.agent.application.AgentIntent;
import com.atlas.enterprise.agent.application.AgentMessageRole;
import com.atlas.enterprise.agent.application.AgentMessageView;
import com.atlas.enterprise.agent.application.AgentResponseType;
import com.atlas.enterprise.agent.application.AgentStoredMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentStoredMessageResponse(
    UUID messageId,
    AgentMessageRole role,
    String content,
    AgentResponseType responseType,
    AgentIntent parsedIntent,
    String companyQuery,
    UUID taskId,
    List<AgentMessageView.RequiredInput> requiredInputs,
    List<AgentMessageView.SuggestedAction> suggestedActions,
    Instant createdAt
) {
    public static AgentStoredMessageResponse from(AgentStoredMessage message) {
        return new AgentStoredMessageResponse(
            message.messageId(),
            message.role(),
            message.content(),
            message.responseType(),
            message.parsedIntent(),
            message.companyQuery(),
            message.taskId(),
            message.requiredInputs(),
            message.suggestedActions(),
            message.createdAt()
        );
    }
}
