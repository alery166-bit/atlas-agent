package com.atlas.enterprise.task;

import java.time.Instant;
import java.util.UUID;

public record TaskStep(
    UUID taskStepId,
    UUID taskId,
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
}
