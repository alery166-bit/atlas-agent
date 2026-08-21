package com.atlas.enterprise.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ErrorResponse(
    String code,
    String message,
    UUID taskId,
    String traceId,
    boolean retryable,
    Map<String, Object> details,
    Instant occurredAt
) {
}
