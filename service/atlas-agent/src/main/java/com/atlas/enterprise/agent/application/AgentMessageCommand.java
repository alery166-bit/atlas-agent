package com.atlas.enterprise.agent.application;

import java.util.UUID;

public record AgentMessageCommand(
    UUID conversationId,
    String message,
    UUID taskId,
    String previousReportFileId,
    String operatorId,
    String idempotencyKey
) {
    public AgentMessageCommand(
        String message,
        UUID taskId,
        String previousReportFileId,
        String operatorId,
        String idempotencyKey
    ) {
        this(
            null,
            message,
            taskId,
            previousReportFileId,
            operatorId,
            idempotencyKey
        );
    }
}
