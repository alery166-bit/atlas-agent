package com.atlas.enterprise.company;

import java.time.Instant;

public record SourceStatus(
    String sourceSystem,
    String sourceName,
    QueryStatus queryStatus,
    long recordCount,
    Instant dataAsOf,
    Instant fetchedAt,
    String errorCode,
    String message
) {
    public boolean failed() {
        return queryStatus == QueryStatus.FAILED;
    }
}
