package com.atlas.enterprise.task.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TaskEventRecord(
    long eventId,
    UUID taskId,
    String type,
    Map<String, String> payload,
    Instant occurredAt
) {
}
