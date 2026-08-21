package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.InvestigationTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository {
    InvestigationTask save(InvestigationTask task);
    Optional<InvestigationTask> findById(UUID taskId);
    Optional<InvestigationTask> findByIdempotencyKey(String idempotencyKey);
    List<InvestigationTask> search(TaskSearchCriteria criteria);
}
