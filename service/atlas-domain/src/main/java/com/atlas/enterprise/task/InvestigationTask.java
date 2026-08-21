package com.atlas.enterprise.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class InvestigationTask {
    private final UUID taskId;
    private final String taskNo;
    private final TaskIntent intent;
    private final String originalPrompt;
    private final String companyQuery;
    private final String previousReportFileId;
    private final String operatorId;
    private final String idempotencyKey;
    private final Instant createdAt;
    private UUID atlasCompanyId;
    private TaskStatus status;
    private String currentStep;
    private String failedStep;
    private TaskErrorCode errorCode;
    private Instant updatedAt;
    private Instant completedAt;

    private InvestigationTask(
        UUID taskId,
        String taskNo,
        TaskIntent intent,
        TaskStatus status,
        String originalPrompt,
        String companyQuery,
        String previousReportFileId,
        String operatorId,
        String idempotencyKey,
        UUID atlasCompanyId,
        String currentStep,
        String failedStep,
        TaskErrorCode errorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        this.taskId = Objects.requireNonNull(taskId);
        this.taskNo = requireText(taskNo, "taskNo");
        this.intent = Objects.requireNonNull(intent);
        this.status = Objects.requireNonNull(status);
        this.originalPrompt = requireText(originalPrompt, "originalPrompt");
        this.companyQuery = requireText(companyQuery, "companyQuery");
        // Kept only for restoring historical tasks created before direct report
        // generation. New tasks do not require or populate this value.
        this.previousReportFileId = blankToNull(previousReportFileId);
        this.operatorId = requireText(operatorId, "operatorId");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.atlasCompanyId = atlasCompanyId;
        this.currentStep = currentStep;
        this.failedStep = failedStep;
        this.errorCode = errorCode;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.completedAt = completedAt;
    }

    public static InvestigationTask create(
        UUID taskId,
        String taskNo,
        String prompt,
        String companyQuery,
        String previousReportFileId,
        String operatorId,
        String idempotencyKey,
        Instant now
    ) {
        return new InvestigationTask(
            taskId,
            taskNo,
            TaskIntent.RISK_REPORT_UPDATE,
            TaskStatus.CREATED,
            prompt,
            companyQuery,
            previousReportFileId,
            operatorId,
            idempotencyKey,
            null,
            null,
            null,
            null,
            now,
            now,
            null
        );
    }

    public static InvestigationTask restore(
        UUID taskId,
        String taskNo,
        TaskIntent intent,
        TaskStatus status,
        String originalPrompt,
        String companyQuery,
        String previousReportFileId,
        String operatorId,
        String idempotencyKey,
        UUID atlasCompanyId,
        String currentStep,
        String failedStep,
        TaskErrorCode errorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        return new InvestigationTask(
            taskId,
            taskNo,
            intent,
            status,
            originalPrompt,
            companyQuery,
            previousReportFileId,
            operatorId,
            idempotencyKey,
            atlasCompanyId,
            currentStep,
            failedStep,
            errorCode,
            createdAt,
            updatedAt,
            completedAt
        );
    }

    public void transitionTo(TaskStatus target, String step, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidTaskTransitionException(status, target);
        }
        status = target;
        currentStep = step;
        failedStep = null;
        errorCode = null;
        updatedAt = now;
        if (target.isTerminal()) {
            completedAt = now;
        }
    }

    public void bindCompany(UUID companyId, Instant now) {
        atlasCompanyId = Objects.requireNonNull(companyId);
        updatedAt = Objects.requireNonNull(now);
    }

    public void fail(TaskStatus failureStatus, String step, TaskErrorCode code, Instant now) {
        if (failureStatus != TaskStatus.SOURCE_FAILED
            && failureStatus != TaskStatus.MODEL_FAILED
            && failureStatus != TaskStatus.REPORT_FAILED) {
            throw new IllegalArgumentException("Not a failure status: " + failureStatus);
        }
        if (!status.canTransitionTo(failureStatus)) {
            throw new InvalidTaskTransitionException(status, failureStatus);
        }
        status = failureStatus;
        failedStep = requireText(step, "step");
        currentStep = step;
        errorCode = Objects.requireNonNull(code);
        updatedAt = now;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID taskId() { return taskId; }
    public String taskNo() { return taskNo; }
    public TaskIntent intent() { return intent; }
    public TaskStatus status() { return status; }
    public String originalPrompt() { return originalPrompt; }
    public String companyQuery() { return companyQuery; }
    public String previousReportFileId() { return previousReportFileId; }
    public String operatorId() { return operatorId; }
    public String idempotencyKey() { return idempotencyKey; }
    public UUID atlasCompanyId() { return atlasCompanyId; }
    public String currentStep() { return currentStep; }
    public String failedStep() { return failedStep; }
    public TaskErrorCode errorCode() { return errorCode; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant completedAt() { return completedAt; }
}
