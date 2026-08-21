package com.atlas.enterprise.agent.api;

import com.atlas.enterprise.agent.application.AgentMessageApplicationService;
import com.atlas.enterprise.agent.application.AgentMessageCommand;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/messages")
public class AgentMessageController {
    private final AgentMessageApplicationService messages;

    public AgentMessageController(AgentMessageApplicationService messages) {
        this.messages = messages;
    }

    @PostMapping
    public AgentMessageResponse send(
        @Valid @RequestBody AgentMessageRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(
            value = "X-Operator-Id",
            defaultValue = "local-operator"
        ) String operatorId
    ) {
        return AgentMessageResponse.from(messages.handle(
            new AgentMessageCommand(
                request.message(),
                request.taskId(),
                request.previousReportFileId(),
                operatorId,
                idempotencyKey
            )
        ));
    }
}
