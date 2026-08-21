package com.atlas.enterprise.company;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record RiskEvent(
    String eventType,
    String sourceSystem,
    String sourceName,
    String sourceRecordId,
    String occurredAt,
    String title,
    String summary,
    Instant dataAsOf,
    Instant fetchedAt,
    Map<String, String> rawFields
) {
    public RiskEvent {
        rawFields = rawFields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(rawFields));
    }
}
