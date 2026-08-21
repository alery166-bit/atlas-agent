package com.atlas.enterprise.task.application;

import java.util.UUID;

public interface TaskWorkflowRunner {
    TaskView run(UUID taskId, String traceId, String workerId);

    TaskView retry(UUID taskId, String traceId, String workerId);

    TaskView confirmSubject(
        UUID taskId,
        String sourceSystem,
        String sourceEntityId,
        String traceId,
        String operatorId
    );
}
