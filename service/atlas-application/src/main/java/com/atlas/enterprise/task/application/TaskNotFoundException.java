package com.atlas.enterprise.task.application;

import java.util.UUID;

public final class TaskNotFoundException extends RuntimeException {
    private final UUID taskId;

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public UUID taskId() {
        return taskId;
    }
}
