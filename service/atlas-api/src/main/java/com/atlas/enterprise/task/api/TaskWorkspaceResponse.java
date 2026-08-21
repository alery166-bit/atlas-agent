package com.atlas.enterprise.task.api;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.report.api.ReportVersionResponse;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.SubjectDataConflictResolution;
import com.atlas.enterprise.task.application.TaskWorkspaceView;
import java.util.List;

public record TaskWorkspaceResponse(
    TaskResponse task,
    DataSnapshot dataSnapshot,
    List<TaskWorkspaceView.SubjectDataConflict> subjectDataConflicts,
    SubjectDataConflictResolution subjectDataConflictResolution,
    RiskScoreSnapshot riskScore,
    TaskWorkspaceView.PreviousReportScore previousReportScore,
    TaskWorkspaceView.EvidenceProgress evidenceProgress,
    OperatorConfirmation latestConfirmation,
    TaskWorkspaceView.ConfirmationState confirmationState,
    boolean confirmationReady,
    boolean reportGenerationReady,
    List<TaskWorkspaceView.ReadinessBlocker> readinessBlockers,
    TaskWorkspaceView.NextAction nextAction,
    List<ReportVersionResponse> reports,
    List<TaskStepResponse> steps
) {
    public static TaskWorkspaceResponse from(TaskWorkspaceView view) {
        return new TaskWorkspaceResponse(
            TaskResponse.from(view.task()),
            view.dataSnapshot(),
            view.subjectDataConflicts(),
            view.subjectDataConflictResolution(),
            view.riskScore(),
            view.previousReportScore(),
            view.evidenceProgress(),
            view.latestConfirmation(),
            view.confirmationState(),
            view.confirmationReady(),
            view.reportGenerationReady(),
            view.readinessBlockers(),
            view.nextAction(),
            view.reports().stream()
                .map(ReportVersionResponse::from)
                .toList(),
            view.steps().stream()
                .map(TaskStepResponse::from)
                .toList()
        );
    }
}
