package com.atlas.enterprise.agent.application;

import java.util.UUID;

public class AgentConversationNotFoundException extends RuntimeException {
    private final UUID conversationId;

    public AgentConversationNotFoundException(UUID conversationId) {
        super("Agent conversation not found: " + conversationId);
        this.conversationId = conversationId;
    }

    public UUID conversationId() {
        return conversationId;
    }
}
