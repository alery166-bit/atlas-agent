package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskStep;
import com.atlas.enterprise.task.TaskStepName;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskStepRepository {
    TaskStep start(
        UUID taskId,
        TaskStepName stepName,
        String inputHash,
        String traceId,
        Instant now
    );

    void complete(UUID taskStepId, String outputRef, Instant now);

    void skip(UUID taskStepId, String outputRef, Instant now);

    void fail(
        UUID taskStepId,
        TaskErrorCode errorCode,
        String errorMessage,
        Instant now
    );

    Optional<TaskStep> findLatest(UUID taskId, TaskStepName stepName);

    List<TaskStep> findByTaskId(UUID taskId);
}
