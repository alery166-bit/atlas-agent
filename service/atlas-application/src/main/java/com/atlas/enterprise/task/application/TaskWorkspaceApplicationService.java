package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.report.port.ReportVersionRepository;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.port.RiskScoreSnapshotRepository;
import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.OperatorReviewState;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.port.OperatorConfirmationRepository;
import com.atlas.enterprise.task.port.SubjectDataConflictResolutionRepository;
import com.atlas.enterprise.task.port.TaskRepository;
import com.atlas.enterprise.task.port.TaskStepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskWorkspaceApplicationService {
    private final TaskRepository tasks;
    private final TaskStepRepository steps;
    private final DataSnapshotRepository snapshots;
    private final RiskScoreSnapshotRepository scores;
    private final PublicIntelligenceRepository publicIntelligence;
    private final OperatorConfirmationRepository confirmations;
    private final SubjectDataConflictResolutionRepository conflictResolutions;
    private final ReportVersionRepository reports;
    private final OperatorReviewStateService reviewStates;

    public TaskWorkspaceApplicationService(
        TaskRepository tasks,
        TaskStepRepository steps,
        DataSnapshotRepository snapshots,
        RiskScoreSnapshotRepository scores,
        PublicIntelligenceRepository publicIntelligence,
        OperatorConfirmationRepository confirmations,
        SubjectDataConflictResolutionRepository conflictResolutions,
        ReportVersionRepository reports,
        OperatorReviewStateService reviewStates
    ) {
        this.tasks = tasks;
        this.steps = steps;
        this.snapshots = snapshots;
        this.scores = scores;
        this.publicIntelligence = publicIntelligence;
        this.confirmations = confirmations;
        this.conflictResolutions = conflictResolutions;
        this.reports = reports;
        this.reviewStates = reviewStates;
    }

    @Transactional(readOnly = true)
    public TaskWorkspaceView get(UUID taskId) {
        InvestigationTask task = tasks.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId).orElse(null);
        RiskScoreSnapshot score = scores.findLatestByTaskId(taskId).orElse(null);
        List<PublicEvidence> evidence =
            publicIntelligence.findEvidenceByTaskId(taskId);
        TaskWorkspaceView.EvidenceProgress progress =
            TaskReadinessEvaluator.evidenceProgress(
                evidence,
                publicIntelligence.findContentReferencesByTaskId(taskId)
            );
        OperatorConfirmation latest =
            confirmations.findLatestByTaskId(taskId).orElse(null);
        var subjectConflicts = SubjectDataConflictDetector.detect(snapshot);
        var conflictResolution = conflictResolutions
            .findLatestByTaskId(taskId)
            .filter(item -> snapshot != null && item.matches(snapshot.snapshotId()))
            .orElse(null);

        List<TaskWorkspaceView.ReadinessBlocker> blockers =
            TaskReadinessEvaluator.readinessBlockers(
                snapshot,
                score,
                progress,
                conflictResolution != null
            );
        OperatorReviewState currentState =
            TaskReadinessEvaluator.hasInputBlocker(blockers)
                ? null
                : reviewStates.current(taskId);
        TaskWorkspaceView.ConfirmationState confirmationState =
            TaskReadinessEvaluator.confirmationState(latest, currentState);
        List<ReportVersion> reportVersions = reports.findByTaskId(taskId);
        boolean hasCurrentGeneratedReport = reportVersions.stream().anyMatch(
            report -> ReportReadinessEvaluator.isCurrentGeneratedReport(
                report,
                snapshot,
                score,
                latest,
                confirmationState
            )
        );
        boolean confirmationReady = blockers.isEmpty();
        boolean reportReady = confirmationState
            == TaskWorkspaceView.ConfirmationState.VALID
            && (task.status() == TaskStatus.WAITING_OPERATOR_CONFIRMATION
                || task.status() == TaskStatus.REPORT_FAILED
                || (task.status() == TaskStatus.COMPLETED
                    && !hasCurrentGeneratedReport));

        return new TaskWorkspaceView(
            TaskView.from(task),
            snapshot,
            subjectConflicts,
            conflictResolution,
            score,
            null,
            progress,
            latest,
            confirmationState,
            confirmationReady,
            reportReady,
            blockers,
            TaskActionResolver.resolve(
                task.status(),
                blockers,
                confirmationState,
                hasCurrentGeneratedReport
            ),
            reportVersions,
            steps.findByTaskId(taskId)
        );
    }

}
