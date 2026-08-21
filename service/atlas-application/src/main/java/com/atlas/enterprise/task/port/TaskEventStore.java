package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.application.TaskEventRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TaskEventStore {
    TaskEventRecord append(UUID taskId, String type, Map<String, String> payload, Instant occurredAt);
    List<TaskEventRecord> findAfter(UUID taskId, long eventIdExclusive);
}
