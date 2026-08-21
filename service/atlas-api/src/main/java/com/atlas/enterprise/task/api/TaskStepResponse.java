package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskStep;
import com.atlas.enterprise.task.TaskStepName;
import com.atlas.enterprise.task.TaskStepStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskStepResponse(
    UUID taskStepId,
    TaskStepName stepName,
    int sequenceNo,
    TaskStepStatus status,
    int attemptNo,
    String inputHash,
    String outputRef,
    String traceId,
    TaskErrorCode errorCode,
    String errorMessage,
    Instant startedAt,
    Instant endedAt
) {
    static TaskStepResponse from(TaskStep step) {
        return new TaskStepResponse(
            step.taskStepId(),
            step.stepName(),
            step.sequenceNo(),
            step.status(),
            step.attemptNo(),
            step.inputHash(),
            step.outputRef(),
            step.traceId(),
            step.errorCode(),
            step.errorMessage(),
            step.startedAt(),
            step.endedAt()
        );
    }
}
