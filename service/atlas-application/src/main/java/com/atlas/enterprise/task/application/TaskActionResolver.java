package com.atlas.enterprise.task.application;

import com.atlas.enterprise.task.TaskStatus;
import java.util.List;

public final class TaskActionResolver {
    private TaskActionResolver() {
    }

    public static TaskWorkspaceView.NextAction resolve(
        TaskStatus status,
        List<TaskWorkspaceView.ReadinessBlocker> blockers,
        TaskWorkspaceView.ConfirmationState confirmationState,
        boolean hasCurrentGeneratedReport
    ) {
        return switch (status) {
            case CREATED, RESOLVING_SUBJECT, LOADING_PREVIOUS_REPORT,
                COLLECTING_STRUCTURED_DATA, SEARCHING_PUBLIC_INTELLIGENCE ->
                TaskWorkspaceView.NextAction.EXECUTE_TASK;
            case WAITING_SUBJECT_CONFIRMATION ->
                TaskWorkspaceView.NextAction.CONFIRM_SUBJECT;
            case WAITING_SUBJECT_DATA_REVIEW ->
                TaskWorkspaceView.NextAction.REVIEW_SUBJECT_DATA;
            case CALCULATING_RISK, WAITING_OPERATOR_CONFIRMATION ->
                reviewAction(blockers, confirmationState);
            case SOURCE_FAILED, MODEL_FAILED ->
                TaskWorkspaceView.NextAction.RETRY_TASK;
            case REPORT_FAILED ->
                confirmationState == TaskWorkspaceView.ConfirmationState.VALID
                    ? TaskWorkspaceView.NextAction.RETRY_REPORT
                    : reviewAction(blockers, confirmationState);
            case GENERATING_REPORT -> TaskWorkspaceView.NextAction.WAIT;
            case COMPLETED -> hasCurrentGeneratedReport
                ? TaskWorkspaceView.NextAction.DOWNLOAD_REPORT
                : reviewAction(blockers, confirmationState);
            case CANCELLED -> TaskWorkspaceView.NextAction.NONE;
        };
    }

    private static TaskWorkspaceView.NextAction reviewAction(
        List<TaskWorkspaceView.ReadinessBlocker> blockers,
        TaskWorkspaceView.ConfirmationState confirmationState
    ) {
        if (blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.SUBJECT_DATA_CONFLICT
        )) {
            return TaskWorkspaceView.NextAction.REVIEW_SUBJECT_DATA;
        }
        if (blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.UNVERIFIED_EVIDENCE
        )) {
            return TaskWorkspaceView.NextAction.REVIEW_EVIDENCE;
        }
        if (blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.RISK_SCORE_MISSING
        ) || blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.RISK_SCORE_NOT_FROM_LATEST_DATA
        )) {
            return TaskWorkspaceView.NextAction.CALCULATE_RISK;
        }
        if (confirmationState != TaskWorkspaceView.ConfirmationState.VALID) {
            return TaskWorkspaceView.NextAction.CONFIRM_REVIEW;
        }
        return TaskWorkspaceView.NextAction.GENERATE_REPORT;
    }
}
