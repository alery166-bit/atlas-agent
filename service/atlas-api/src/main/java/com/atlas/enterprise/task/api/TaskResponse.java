package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskIntent;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.application.TaskView;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
    UUID taskId,
    String taskNo,
    TaskIntent intent,
    TaskStatus status,
    String originalPrompt,
    String companyQuery,
    String previousReportFileId,
    UUID atlasCompanyId,
    String currentStep,
    String failedStep,
    TaskErrorCode errorCode,
    String operatorId,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    String eventsUrl
) {
    static TaskResponse from(TaskView view) {
        return new TaskResponse(
            view.taskId(),
            view.taskNo(),
            view.intent(),
            view.status(),
            view.originalPrompt(),
            view.companyQuery(),
            view.previousReportFileId(),
            view.atlasCompanyId(),
            view.currentStep(),
            view.failedStep(),
            view.errorCode(),
            view.operatorId(),
            view.createdAt(),
            view.updatedAt(),
            view.completedAt(),
            "/api/tasks/" + view.taskId() + "/events"
        );
    }
}
