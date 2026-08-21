package com.atlas.enterprise.task.application;

import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskIntent;
import com.atlas.enterprise.task.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskView(
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
    Instant completedAt
) {
    public static TaskView from(InvestigationTask task) {
        return new TaskView(
            task.taskId(),
            task.taskNo(),
            task.intent(),
            task.status(),
            task.originalPrompt(),
            task.companyQuery(),
            task.previousReportFileId(),
            task.atlasCompanyId(),
            task.currentStep(),
            task.failedStep(),
            task.errorCode(),
            task.operatorId(),
            task.createdAt(),
            task.updatedAt(),
            task.completedAt()
        );
    }
}
