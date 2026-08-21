package com.atlas.enterprise.agent.application;

public class AgentTaskAccessDeniedException extends RuntimeException {
    public AgentTaskAccessDeniedException() {
        super("Task is not available to the current operator");
    }
}
