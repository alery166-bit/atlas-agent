package com.atlas.enterprise.agent.application;

import com.atlas.enterprise.agent.port.AgentConversationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConversationApplicationService {
    private static final int MAX_CONVERSATIONS = 100;
    private static final int MAX_MESSAGES = 500;

    private final AgentConversationRepository conversations;
    private final Clock clock;

    public AgentConversationApplicationService(
        AgentConversationRepository conversations,
        Clock clock
    ) {
        this.conversations = conversations;
        this.clock = clock;
    }

    @Transactional
    public AgentConversation create(String operatorId, String title) {
        requireOperator(operatorId);
        Instant now = clock.instant();
        return conversations.saveConversation(new AgentConversation(
            UUID.randomUUID(),
            operatorId,
            normalizeTitle(title),
            null,
            null,
            null,
            now,
            now
        ));
    }

    public AgentConversation get(UUID conversationId, String operatorId) {
        requireOperator(operatorId);
        AgentConversation conversation = conversations
            .findConversation(conversationId)
            .orElseThrow(() ->
                new AgentConversationNotFoundException(conversationId)
            );
        authorize(conversation, operatorId);
        return conversation;
    }

    public List<AgentConversation> list(String operatorId) {
        requireOperator(operatorId);
        return conversations.findConversations(
            operatorId,
            MAX_CONVERSATIONS
        );
    }

    @Transactional
    public void archive(UUID conversationId, String operatorId) {
        requireOperator(operatorId);
        AgentConversation conversation = get(conversationId, operatorId);
        if (!conversations.archiveConversation(
            conversation.conversationId(), operatorId, clock.instant()
        )) {
            throw new AgentConversationNotFoundException(conversationId);
        }
    }

    public List<AgentStoredMessage> messages(
        UUID conversationId,
        String operatorId
    ) {
        get(conversationId, operatorId);
        return conversations.findMessages(conversationId, MAX_MESSAGES);
    }

    static void authorize(
        AgentConversation conversation,
        String operatorId
    ) {
        if (!conversation.operatorId().equals(operatorId)) {
            throw new AgentTaskAccessDeniedException();
        }
    }

    private static void requireOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new AgentMessageValidationException(
                "X-Operator-Id must not be blank"
            );
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "新建企业风险排查";
        }
        String normalized = title.trim();
        return normalized.substring(0, Math.min(normalized.length(), 128));
    }
}
