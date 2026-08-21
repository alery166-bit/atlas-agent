package com.atlas.enterprise.agent.port;

import com.atlas.enterprise.agent.application.AgentConversation;
import com.atlas.enterprise.agent.application.AgentStoredMessage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface AgentConversationRepository {
    AgentConversation saveConversation(AgentConversation conversation);

    Optional<AgentConversation> findConversation(UUID conversationId);

    List<AgentConversation> findConversations(String operatorId, int limit);

    boolean archiveConversation(UUID conversationId, String operatorId, Instant archivedAt);

    AgentStoredMessage appendMessage(AgentStoredMessage message);

    List<AgentStoredMessage> findMessages(UUID conversationId, int limit);

    Optional<AgentStoredMessage> findAssistantByIdempotencyKey(
        UUID conversationId,
        String idempotencyKey
    );
}
