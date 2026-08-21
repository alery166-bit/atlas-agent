package com.atlas.enterprise.task.application;

import java.util.UUID;

public class TaskWorkflowConflictException extends RuntimeException {
    private final UUID taskId;

    public TaskWorkflowConflictException(UUID taskId, String message) {
        super(message);
        this.taskId = taskId;
    }

    public UUID taskId() {
        return taskId;
    }
}
