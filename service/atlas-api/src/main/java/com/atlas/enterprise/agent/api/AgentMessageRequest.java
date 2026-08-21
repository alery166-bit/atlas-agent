package com.atlas.enterprise.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AgentMessageRequest(
    @NotBlank @Size(max = 4000) String message,
    UUID taskId,
    @Size(max = 256) String previousReportFileId
) {
}
