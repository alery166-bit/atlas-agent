package com.atlas.enterprise.agent.api;

import jakarta.validation.constraints.Size;

public record CreateAgentConversationRequest(
    @Size(max = 128) String title
) {
}
