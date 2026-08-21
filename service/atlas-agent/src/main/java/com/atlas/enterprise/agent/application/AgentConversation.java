package com.atlas.enterprise.agent.application;

import java.time.Instant;
import java.util.UUID;

public record AgentConversation(
    UUID conversationId,
    String operatorId,
    String title,
    UUID taskId,
    String companyQuery,
    String previousReportFileId,
    Instant createdAt,
    Instant updatedAt
) {
    public AgentConversation withContext(
        UUID newTaskId,
        String newCompanyQuery,
        String newPreviousReportFileId,
        Instant now
    ) {
        return new AgentConversation(
            conversationId,
            operatorId,
            title,
            newTaskId == null ? taskId : newTaskId,
            newCompanyQuery == null ? companyQuery : newCompanyQuery,
            newPreviousReportFileId == null
                ? previousReportFileId
                : newPreviousReportFileId,
            createdAt,
            now
        );
    }
}
