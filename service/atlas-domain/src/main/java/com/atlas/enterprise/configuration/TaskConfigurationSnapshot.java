package com.atlas.enterprise.configuration;

import java.time.Instant;
import java.util.UUID;

public record TaskConfigurationSnapshot(
    UUID configSnapshotId,
    UUID taskId,
    String environment,
    String manifestJson,
    String contentHash,
    Instant frozenAt
) {
    public TaskConfigurationSnapshot {
        if (configSnapshotId == null || taskId == null || frozenAt == null
            || environment == null || environment.isBlank()
            || manifestJson == null || manifestJson.isBlank()
            || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("Invalid task configuration snapshot");
        }
        environment = environment.trim().toUpperCase();
    }
}
