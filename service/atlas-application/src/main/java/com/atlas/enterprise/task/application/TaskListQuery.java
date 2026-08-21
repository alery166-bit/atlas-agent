package com.atlas.enterprise.task.application;

import com.atlas.enterprise.task.TaskStatus;
import java.util.Set;

public record TaskListQuery(
    String query,
    Set<TaskStatus> statuses,
    String operatorId,
    int pageSize,
    String cursor
) {
    public TaskListQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
}
