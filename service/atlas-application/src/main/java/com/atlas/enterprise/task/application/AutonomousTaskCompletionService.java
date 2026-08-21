package com.atlas.enterprise.task.application;

import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.intelligence.application.PublicIntelligenceApplicationService;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.report.application.ReportApplicationService;
import com.atlas.enterprise.risk.application.RiskScoreApplicationService;
import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import com.atlas.enterprise.task.port.SubjectDataConflictResolutionRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Completes the deterministic part of an investigation after every evidence
 * item has a decision. The system identity is intentionally explicit so that
 * automatic and human confirmations remain distinguishable in the audit log.
 */
@Service
public class AutonomousTaskCompletionService {
    public static final String SYSTEM_OPERATOR_ID = "atlas-agent:auto";

    private final TaskRepository tasks;
    private final PublicIntelligenceApplicationService publicIntelligence;
    private final DataSnapshotRepository snapshots;
    private final RiskScoreApplicationService riskScores;
    private final OperatorConfirmationApplicationService confirmations;
    private final ReportApplicationService reports;
    private final SubjectDataConflictResolutionRepository conflictResolutions;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;

    public AutonomousTaskCompletionService(
        TaskRepository tasks,
        DataSnapshotRepository snapshots,
        PublicIntelligenceApplicationService publicIntelligence,
        RiskScoreApplicationService riskScores,
        OperatorConfirmationApplicationService confirmations,
        ReportApplicationService reports,
        SubjectDataConflictResolutionRepository conflictResolutions,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        Clock clock
    ) {
        this.tasks = tasks;
        this.snapshots = snapshots;
        this.publicIntelligence = publicIntelligence;
        this.riskScores = riskScores;
        this.confirmations = confirmations;
        this.reports = reports;
        this.conflictResolutions = conflictResolutions;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public CompletionResult completeIfReady(UUID taskId) {
        InvestigationTask task = requireTask(taskId);
        if (task.status() == TaskStatus.COMPLETED) {
            return new CompletionResult(Status.ALREADY_COMPLETED, 0, null, null);
        }
        var snapshot = snapshots.findLatestByTaskId(taskId).orElse(null);
        boolean conflictResolved = snapshot != null && conflictResolutions
            .findLatestByTaskId(taskId)
            .filter(item -> item.matches(snapshot.snapshotId()))
            .isPresent();
        var subjectDataConflicts = SubjectDataConflictDetector.detect(snapshot);
        if (!conflictResolved && !subjectDataConflicts.isEmpty()) {
            if (task.status() == TaskStatus.CALCULATING_RISK) {
                var now = clock.instant();
                task.transitionTo(
                    TaskStatus.WAITING_SUBJECT_DATA_REVIEW,
                    "REVIEW_SUBJECT_DATA",
                    now
                );
                tasks.save(task);
                publish(taskId, "task.status.changed", Map.of(
                    "status", TaskStatus.WAITING_SUBJECT_DATA_REVIEW.name(),
                    "currentStep", "REVIEW_SUBJECT_DATA"
                ), now);
            }
            publishActionRequired(
                taskId,
                "REVIEW_SUBJECT_DATA",
                "SUBJECT_DATA_CONFLICT",
                null
            );
            return new CompletionResult(
                Status.WAITING_MANUAL_REVIEW,
                0,
                null,
                "Critical subject data conflicts require operator review"
            );
        }
        if (task.status() == TaskStatus.WAITING_SUBJECT_DATA_REVIEW
            && subjectDataConflicts.isEmpty()) {
            var now = clock.instant();
            task.transitionTo(
                TaskStatus.CALCULATING_RISK,
                "CALCULATE_RISK",
                now
            );
            tasks.save(task);
            publish(taskId, "task.subject_data_conflict.cleared", Map.of(
                "status", TaskStatus.CALCULATING_RISK.name(),
                "currentStep", "CALCULATE_RISK",
                "reason", "CURRENT_MASTER_SUPERSEDES_HISTORICAL_CHANGE"
            ), now);
        }
        int pendingEvidence = (int) publicIntelligence.evidence(taskId).stream()
            .filter(item -> item.verificationStatus()
                == EvidenceVerificationStatus.UNVERIFIED)
            .count();
        if (pendingEvidence > 0) {
            publishActionRequired(
                taskId,
                "REVIEW_EVIDENCE",
                "UNCERTAIN_PUBLIC_EVIDENCE",
                null
            );
            return new CompletionResult(
                Status.WAITING_MANUAL_REVIEW,
                pendingEvidence,
                null,
                null
            );
        }
        if (task.status() != TaskStatus.CALCULATING_RISK
            && task.status() != TaskStatus.WAITING_OPERATOR_CONFIRMATION
            && task.status() != TaskStatus.REPORT_FAILED) {
            return new CompletionResult(
                Status.NOT_READY,
                0,
                null,
                "Task status does not allow automatic completion: " + task.status()
            );
        }

        try {
            riskScores.calculate(
                taskId,
                publicIntelligence.confirmedRiskEvents(taskId)
            );
            confirmations.confirm(
                taskId,
                "Atlas已完成主体、数据、公开证据和规则评分的自动研判。",
                SYSTEM_OPERATOR_ID
            );
            ReportVersion report = reports.generate(taskId, SYSTEM_OPERATOR_ID);
            TaskEventRecord event = events.append(
                taskId,
                "task.automatic.completion.completed",
                Map.of(
                    "reportId", report.reportId().toString(),
                    "reportVersion", Integer.toString(report.reportVersionNo()),
                    "operatorId", SYSTEM_OPERATOR_ID
                ),
                clock.instant()
            );
            eventPublisher.publish(event);
            return new CompletionResult(
                Status.COMPLETED,
                0,
                report.reportId(),
                null
            );
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            publishActionRequired(
                taskId,
                "REVIEW_TASK",
                "AUTOMATIC_COMPLETION_FAILED",
                message
            );
            return new CompletionResult(Status.FAILED, 0, null, message);
        }
    }

    private void publishActionRequired(
        UUID taskId,
        String action,
        String reason,
        String message
    ) {
        Map<String, String> payload = message == null
            ? Map.of("action", action, "reason", reason)
            : Map.of(
                "action", "REVIEW_TASK",
                "reason", reason,
                "message", message
            );
        TaskEventRecord event = events.append(
            taskId,
            "operator.action.required",
            payload,
            clock.instant()
        );
        eventPublisher.publish(event);
    }

    private void publish(
        UUID taskId,
        String type,
        Map<String, String> payload,
        java.time.Instant occurredAt
    ) {
        TaskEventRecord event = events.append(taskId, type, payload, occurredAt);
        eventPublisher.publish(event);
    }

    private InvestigationTask requireTask(UUID taskId) {
        return tasks.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    public record CompletionResult(
        Status status,
        int pendingEvidenceCount,
        UUID reportId,
        String message
    ) {
    }

    public enum Status {
        COMPLETED,
        ALREADY_COMPLETED,
        WAITING_MANUAL_REVIEW,
        NOT_READY,
        FAILED
    }
}
