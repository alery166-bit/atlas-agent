package com.atlas.enterprise.task.application;

import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.OperatorReviewState;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.port.OperatorConfirmationRepository;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorConfirmationApplicationService {
    private final TaskRepository tasks;
    private final OperatorConfirmationRepository confirmations;
    private final OperatorReviewStateService reviewStates;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;

    public OperatorConfirmationApplicationService(
        TaskRepository tasks,
        OperatorConfirmationRepository confirmations,
        OperatorReviewStateService reviewStates,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        Clock clock
    ) {
        this.tasks = tasks;
        this.confirmations = confirmations;
        this.reviewStates = reviewStates;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public OperatorConfirmation confirm(
        UUID taskId,
        String note,
        String operatorId
    ) {
        InvestigationTask task = requireTask(taskId);
        if (task.status() != TaskStatus.CALCULATING_RISK
            && task.status() != TaskStatus.WAITING_OPERATOR_CONFIRMATION
            && task.status() != TaskStatus.REPORT_FAILED
            && task.status() != TaskStatus.COMPLETED) {
            throw new TaskWorkflowConflictException(
                taskId,
                "Operator confirmation requires CALCULATING_RISK "
                    + "WAITING_OPERATOR_CONFIRMATION, REPORT_FAILED or "
                    + "COMPLETED with a stale review"
            );
        }
        String operator = requireOperator(operatorId);
        String normalizedNote = normalizeNote(note);
        OperatorReviewState state = reviewStates.current(taskId);
        if (state.unverifiedEvidenceCount() > 0) {
            throw new OperatorConfirmationValidationException(
                "All public evidence must be confirmed or rejected before "
                    + "operator confirmation; unverified count: "
                    + state.unverifiedEvidenceCount()
            );
        }

        var latest = confirmations.findLatestByTaskId(taskId);
        if (latest.isPresent() && latest.get().matches(state)) {
            if (task.status() == TaskStatus.CALCULATING_RISK) {
                transition(task);
            }
            return latest.get();
        }

        Instant now = clock.instant();
        OperatorConfirmation confirmation = confirmations.save(
            new OperatorConfirmation(
                UUID.randomUUID(),
                taskId,
                state.dataSnapshotId(),
                state.scoreSnapshotId(),
                state.stateHash(),
                state.confirmedEvidenceCount(),
                state.rejectedEvidenceCount(),
                operator,
                normalizedNote,
                now
            )
        );
        if (task.status() == TaskStatus.CALCULATING_RISK) {
            transition(task);
        }
        TaskEventRecord event = events.append(
            taskId,
            "operator.confirmation.completed",
            Map.of(
                "confirmationId", confirmation.confirmationId().toString(),
                "operatorId", confirmation.operatorId(),
                "confirmedEvidenceCount",
                Integer.toString(confirmation.confirmedEvidenceCount()),
                "rejectedEvidenceCount",
                Integer.toString(confirmation.rejectedEvidenceCount())
            ),
            now
        );
        eventPublisher.publish(event);
        return confirmation;
    }

    @Transactional(readOnly = true)
    public List<OperatorConfirmation> list(UUID taskId) {
        requireTask(taskId);
        return confirmations.findByTaskId(taskId);
    }

    private void transition(InvestigationTask task) {
        task.transitionTo(
            TaskStatus.WAITING_OPERATOR_CONFIRMATION,
            "WAITING_OPERATOR_CONFIRMATION",
            clock.instant()
        );
        tasks.save(task);
    }

    private InvestigationTask requireTask(UUID taskId) {
        return tasks.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static String requireOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new OperatorConfirmationValidationException(
                "operatorId is required"
            );
        }
        return operatorId.trim();
    }

    private static String normalizeNote(String note) {
        String normalized = note == null ? "" : note.trim();
        if (normalized.length() > 1000) {
            throw new OperatorConfirmationValidationException(
                "note must not exceed 1000 characters"
            );
        }
        return normalized;
    }
}
