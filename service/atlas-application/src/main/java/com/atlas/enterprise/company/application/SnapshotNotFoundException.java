package com.atlas.enterprise.company.application;

import java.util.UUID;

public class SnapshotNotFoundException extends RuntimeException {
    private final UUID taskId;

    public SnapshotNotFoundException(UUID taskId) {
        super("No data snapshot exists for task " + taskId);
        this.taskId = taskId;
    }

    public UUID taskId() {
        return taskId;
    }
}
