package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.application.TaskListPageView;
import com.atlas.enterprise.task.application.TaskWorkspaceView;
import java.util.List;

public record TaskListPageResponse(
    List<TaskListItemResponse> items,
    String nextCursor,
    boolean hasMore,
    int pageSize
) {
    static TaskListPageResponse from(TaskListPageView view) {
        return new TaskListPageResponse(
            view.items().stream().map(TaskListItemResponse::from).toList(),
            view.nextCursor(),
            view.hasMore(),
            view.pageSize()
        );
    }

    public record TaskListItemResponse(
        TaskResponse task,
        String companyName,
        TaskListPageView.RiskSummary risk,
        TaskWorkspaceView.EvidenceProgress evidenceProgress,
        TaskWorkspaceView.ConfirmationState confirmationState,
        List<TaskWorkspaceView.ReadinessBlocker> readinessBlockers,
        TaskWorkspaceView.NextAction nextAction,
        TaskListPageView.ConfirmationSummary latestConfirmation,
        TaskListPageView.ReportSummary latestReport
    ) {
        static TaskListItemResponse from(TaskListPageView.Item item) {
            return new TaskListItemResponse(
                TaskResponse.from(item.task()),
                item.companyName(),
                item.risk(),
                item.evidenceProgress(),
                item.confirmationState(),
                item.readinessBlockers(),
                item.nextAction(),
                item.latestConfirmation(),
                item.latestReport()
            );
        }
    }
}
