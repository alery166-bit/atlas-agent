package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.TaskStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record TaskSearchCriteria(
    String query,
    Set<TaskStatus> statuses,
    String operatorId,
    Instant cursorUpdatedAt,
    UUID cursorTaskId,
    int limit
) {
    public TaskSearchCriteria {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
}
