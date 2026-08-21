package com.atlas.enterprise.configuration;

import java.time.Instant;
import java.util.UUID;

public record ConfigurationDefinition(
    UUID configId,
    String configKey,
    ConfigurationCategory category,
    String displayName,
    String description,
    boolean secretConfig,
    String createdBy,
    Instant createdAt
) {
    public ConfigurationDefinition {
        if (configId == null || category == null || createdAt == null) {
            throw new IllegalArgumentException("Configuration identifiers, category and timestamp are required");
        }
        configKey = required(configKey, "configKey", 128);
        displayName = required(displayName, "displayName", 160);
        createdBy = required(createdBy, "createdBy", 64);
        description = description == null ? "" : description.trim();
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " must contain 1 to " + max + " characters");
        }
        return value.trim();
    }
}
