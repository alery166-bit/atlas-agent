package com.atlas.enterprise.company;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record CompanyChange(
    String sourceRecordId,
    String changeItem,
    String changedAt,
    String beforeValue,
    String afterValue,
    String sourceSystem,
    Instant dataAsOf,
    Instant fetchedAt,
    Map<String, String> rawFields
) {
    public CompanyChange {
        rawFields = rawFields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(rawFields));
    }
}
