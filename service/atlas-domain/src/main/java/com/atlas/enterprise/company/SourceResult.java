package com.atlas.enterprise.company;

import java.util.List;

public record SourceResult<T>(
    QueryStatus queryStatus,
    List<T> records,
    List<SourceStatus> sourceStatuses
) {
    public SourceResult {
        records = records == null ? List.of() : List.copyOf(records);
        sourceStatuses = sourceStatuses == null ? List.of() : List.copyOf(sourceStatuses);
    }

    public boolean failed() {
        return queryStatus == QueryStatus.FAILED
            || sourceStatuses.stream().anyMatch(SourceStatus::failed);
    }
}
