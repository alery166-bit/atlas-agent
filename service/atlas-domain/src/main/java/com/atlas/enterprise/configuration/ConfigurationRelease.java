package com.atlas.enterprise.configuration;

import java.time.Instant;
import java.util.UUID;

public record ConfigurationRelease(
    UUID releaseId,
    UUID configId,
    String environment,
    UUID fromVersionId,
    UUID toVersionId,
    Action action,
    String idempotencyKey,
    String operatorId,
    Instant occurredAt
) {
    public enum Action { PUBLISH, ROLLBACK }

    public ConfigurationRelease {
        if (releaseId == null || configId == null || toVersionId == null
            || action == null || occurredAt == null) {
            throw new IllegalArgumentException("Invalid configuration release");
        }
        environment = required(environment, "environment").toUpperCase();
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        operatorId = required(operatorId, "operatorId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
