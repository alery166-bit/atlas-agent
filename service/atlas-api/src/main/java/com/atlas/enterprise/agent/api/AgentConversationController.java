package com.atlas.enterprise.agent.api;

import com.atlas.enterprise.agent.application.AgentConversationApplicationService;
import com.atlas.enterprise.agent.application.AgentMessageApplicationService;
import com.atlas.enterprise.agent.application.AgentMessageCommand;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/conversations")
public class AgentConversationController {
    private final AgentConversationApplicationService conversations;
    private final AgentMessageApplicationService messages;

    public AgentConversationController(
        AgentConversationApplicationService conversations,
        AgentMessageApplicationService messages
    ) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentConversationResponse create(
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId,
        @Valid @RequestBody(required = false)
        CreateAgentConversationRequest request
    ) {
        return AgentConversationResponse.from(
            conversations.create(
                operatorId,
                request == null ? null : request.title()
            )
        );
    }

    @GetMapping
    public List<AgentConversationResponse> list(
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId
    ) {
        return conversations.list(operatorId)
            .stream()
            .map(AgentConversationResponse::from)
            .toList();
    }

    @GetMapping("/{conversationId}")
    public AgentConversationResponse get(
        @PathVariable UUID conversationId,
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId
    ) {
        return AgentConversationResponse.from(
            conversations.get(conversationId, operatorId)
        );
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(
        @PathVariable UUID conversationId,
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId
    ) {
        conversations.archive(conversationId, operatorId);
    }

    @GetMapping("/{conversationId}/messages")
    public List<AgentStoredMessageResponse> messageHistory(
        @PathVariable UUID conversationId,
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId
    ) {
        return conversations.messages(conversationId, operatorId)
            .stream()
            .map(AgentStoredMessageResponse::from)
            .toList();
    }

    @PostMapping("/{conversationId}/messages")
    public AgentMessageResponse send(
        @PathVariable UUID conversationId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId,
        @Valid @RequestBody AgentMessageRequest request
    ) {
        return AgentMessageResponse.from(messages.handle(
            new AgentMessageCommand(
                conversationId,
                request.message(),
                request.taskId(),
                request.previousReportFileId(),
                operatorId,
                idempotencyKey
            )
        ));
    }
}
