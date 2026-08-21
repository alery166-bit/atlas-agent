package com.atlas.enterprise.configuration;

import java.time.Instant;
import java.util.UUID;

public record ConfigurationBinding(
    UUID configId,
    String environment,
    UUID activeVersionId,
    long rowVersion,
    String updatedBy,
    Instant updatedAt
) {
    public ConfigurationBinding {
        if (configId == null || activeVersionId == null || updatedAt == null || rowVersion < 0) {
            throw new IllegalArgumentException("Invalid configuration binding");
        }
        environment = environment == null ? "" : environment.trim().toUpperCase();
        updatedBy = updatedBy == null ? "" : updatedBy.trim();
        if (environment.isBlank() || updatedBy.isBlank()) {
            throw new IllegalArgumentException("environment and updatedBy are required");
        }
    }
}
