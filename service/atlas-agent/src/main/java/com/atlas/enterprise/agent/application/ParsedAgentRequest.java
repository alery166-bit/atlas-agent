package com.atlas.enterprise.agent.application;

public record ParsedAgentRequest(
    AgentIntent intent,
    String companyQuery,
    String taskReference
) {
}
