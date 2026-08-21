package com.atlas.enterprise.agent.api;

import com.atlas.enterprise.agent.application.AgentConversation;
import java.time.Instant;
import java.util.UUID;

public record AgentConversationResponse(
    UUID conversationId,
    String title,
    UUID taskId,
    String companyQuery,
    String previousReportFileId,
    Instant createdAt,
    Instant updatedAt
) {
    public static AgentConversationResponse from(
        AgentConversation conversation
    ) {
        return new AgentConversationResponse(
            conversation.conversationId(),
            conversation.title(),
            conversation.taskId(),
            conversation.companyQuery(),
            conversation.previousReportFileId(),
            conversation.createdAt(),
            conversation.updatedAt()
        );
    }
}
