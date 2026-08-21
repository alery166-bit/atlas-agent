package com.atlas.enterprise.operations.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PlatformObservabilityPort {
    OperationsSnapshot observe(Instant from, Instant to, int failureLimit);

    List<AuditEntry> audit(AuditFilter filter);

    ConfigurationChange configurationChange(UUID releaseId);

    void recordRetry(UUID taskId, String operatorId, String traceId, Instant occurredAt);

    record OperationsSnapshot(
        Instant from,
        Instant to,
        long totalTasks,
        long completedTasks,
        long activeTasks,
        long waitingTasks,
        long stalledTasks,
        long failedTasks,
        long activityThresholdMinutes,
        Long averageDurationMillis,
        long searchCalls,
        Long modelCalls,
        String modelCallState,
        long modelPromptTokens,
        long modelCompletionTokens,
        long modelTotalTokens,
        long generatedReports,
        long failedReports,
        List<ThroughputPoint> throughput,
        List<StalledTask> stalled,
        List<FailedTask> failures
    ) {
    }

    record ThroughputPoint(String date, long created, long completed, long failed) {
    }

    record FailedTask(
        UUID taskId,
        String taskNo,
        String enterpriseName,
        String status,
        String failedStep,
        String errorCode,
        Instant createdAt,
        Instant updatedAt,
        long searchCalls,
        Long modelCalls,
        String reportStatus,
        boolean retryable
    ) {
    }

    record StalledTask(
        UUID taskId,
        String taskNo,
        String enterpriseName,
        String status,
        String currentStep,
        Instant createdAt,
        Instant updatedAt,
        long stalledMinutes
    ) {
    }

    record AuditFilter(
        UUID taskId,
        String enterprise,
        String operatorId,
        String eventType,
        Instant from,
        Instant to,
        int limit
    ) {
    }

    record AuditEntry(
        String eventId,
        String eventType,
        String action,
        UUID taskId,
        String taskNo,
        String enterpriseName,
        String operatorId,
        String actorType,
        String targetType,
        String targetId,
        String beforeJson,
        String afterJson,
        String detail,
        String traceId,
        Instant occurredAt
    ) {
    }

    record ConfigurationChange(
        UUID releaseId,
        UUID configId,
        String configKey,
        String displayName,
        String environment,
        String action,
        UUID fromVersionId,
        Integer fromVersionNo,
        String beforeJson,
        UUID toVersionId,
        int toVersionNo,
        String afterJson,
        String operatorId,
        Instant occurredAt
    ) {
    }
}
