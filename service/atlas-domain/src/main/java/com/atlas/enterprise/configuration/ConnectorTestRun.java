package com.atlas.enterprise.configuration;

import java.time.Instant;
import java.util.UUID;

public record ConnectorTestRun(
    UUID testId,
    UUID versionId,
    String versionChecksum,
    Status status,
    long latencyMs,
    String message,
    String previewJson,
    String operatorId,
    Instant createdAt
) {
    public enum Status { PASSED, FAILED }

    public ConnectorTestRun {
        if (testId == null || versionId == null || versionChecksum == null
            || versionChecksum.isBlank() || status == null || latencyMs < 0
            || message == null || message.isBlank() || operatorId == null
            || operatorId.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Invalid connector test run");
        }
    }
}
