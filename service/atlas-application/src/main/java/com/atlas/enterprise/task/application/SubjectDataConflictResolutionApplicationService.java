package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.SubjectDataConflictDecision;
import com.atlas.enterprise.task.SubjectDataConflictResolution;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.port.SubjectDataConflictResolutionRepository;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectDataConflictResolutionApplicationService {
    private final TaskRepository tasks;
    private final DataSnapshotRepository snapshots;
    private final SubjectDataConflictResolutionRepository resolutions;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;

    public SubjectDataConflictResolutionApplicationService(
        TaskRepository tasks,
        DataSnapshotRepository snapshots,
        SubjectDataConflictResolutionRepository resolutions,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        Clock clock
    ) {
        this.tasks = tasks;
        this.snapshots = snapshots;
        this.resolutions = resolutions;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public SubjectDataConflictResolution resolve(
        UUID taskId,
        SubjectDataConflictDecision decision,
        String note,
        String operatorId
    ) {
        InvestigationTask task = tasks.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.status() != TaskStatus.CALCULATING_RISK
            && task.status() != TaskStatus.WAITING_SUBJECT_DATA_REVIEW) {
            throw new TaskWorkflowConflictException(
                taskId,
                "Subject data conflict can only be resolved before risk calculation"
            );
        }
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new TaskWorkflowConflictException(
                taskId,
                "Frozen data snapshot is missing"
            ));
        if (SubjectDataConflictDetector.detect(snapshot).isEmpty()) {
            throw new TaskWorkflowConflictException(
                taskId,
                "Current data snapshot has no subject data conflict"
            );
        }
        String normalizedNote = requireNote(note);
        SubjectDataConflictResolution resolution = resolutions
            .findLatestByTaskId(taskId)
            .filter(item -> item.matches(snapshot.snapshotId()))
            .orElseGet(() -> resolutions.save(new SubjectDataConflictResolution(
                UUID.randomUUID(),
                taskId,
                snapshot.snapshotId(),
                decision,
                normalizedNote,
                operatorId,
                clock.instant()
            )));

        if (task.status() == TaskStatus.WAITING_SUBJECT_DATA_REVIEW) {
            Instant now = clock.instant();
            task.transitionTo(TaskStatus.CALCULATING_RISK, "CALCULATE_RISK", now);
            tasks.save(task);
            publish(taskId, "task.status.changed", Map.of(
                "status", TaskStatus.CALCULATING_RISK.name(),
                "currentStep", "CALCULATE_RISK"
            ), now);
        }
        publish(taskId, "subject.data.conflict.resolved", Map.of(
            "resolutionId", resolution.resolutionId().toString(),
            "dataSnapshotId", resolution.dataSnapshotId().toString(),
            "decision", resolution.decision().name(),
            "operatorId", resolution.operatorId()
        ), resolution.resolvedAt());
        return resolution;
    }

    private void publish(
        UUID taskId,
        String type,
        Map<String, String> payload,
        Instant occurredAt
    ) {
        TaskEventRecord event = events.append(taskId, type, payload, occurredAt);
        eventPublisher.publish(event);
    }

    private static String requireNote(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Resolution note is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("Resolution note must not exceed 1000 characters");
        }
        return normalized;
    }
}
