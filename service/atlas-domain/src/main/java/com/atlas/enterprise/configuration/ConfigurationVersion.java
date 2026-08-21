package com.atlas.enterprise.configuration;

import java.time.Instant;
import java.util.UUID;

public record ConfigurationVersion(
    UUID versionId,
    UUID configId,
    int versionNo,
    ConfigurationVersionStatus status,
    String valueJson,
    String secretRef,
    String checksum,
    String validationMessage,
    String createdBy,
    Instant createdAt,
    String validatedBy,
    Instant validatedAt,
    String publishedBy,
    Instant publishedAt,
    long rowVersion
) {
    public ConfigurationVersion {
        if (versionId == null || configId == null || status == null || versionNo < 1
            || createdAt == null || rowVersion < 0) {
            throw new IllegalArgumentException("Invalid configuration version identity or state");
        }
        valueJson = valueJson == null || valueJson.isBlank() ? "{}" : valueJson.trim();
        checksum = required(checksum, "checksum");
        createdBy = required(createdBy, "createdBy");
    }

    public boolean editable() {
        return status == ConfigurationVersionStatus.DRAFT;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
