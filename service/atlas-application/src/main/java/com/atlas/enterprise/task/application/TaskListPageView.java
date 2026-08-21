package com.atlas.enterprise.task.application;

import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskListPageView(
    List<Item> items,
    String nextCursor,
    boolean hasMore,
    int pageSize
) {
    public TaskListPageView {
        items = List.copyOf(items);
    }

    public record Item(
        TaskView task,
        String companyName,
        RiskSummary risk,
        TaskWorkspaceView.EvidenceProgress evidenceProgress,
        TaskWorkspaceView.ConfirmationState confirmationState,
        List<TaskWorkspaceView.ReadinessBlocker> readinessBlockers,
        TaskWorkspaceView.NextAction nextAction,
        ConfirmationSummary latestConfirmation,
        ReportSummary latestReport
    ) {
        public Item {
            readinessBlockers = List.copyOf(readinessBlockers);
        }
    }

    public record RiskSummary(
        UUID scoreSnapshotId,
        BigDecimal originalScore,
        BigDecimal manualScore,
        RiskLevel originalRiskLevel,
        RiskLevel manualRiskLevel,
        Instant calculatedAt
    ) {
    }

    public record ConfirmationSummary(
        UUID confirmationId,
        String operatorId,
        int confirmedEvidenceCount,
        int rejectedEvidenceCount,
        Instant confirmedAt
    ) {
    }

    public record ReportSummary(
        UUID reportId,
        int reportVersionNo,
        ReportStatus status,
        String templateVersion,
        Instant generatedAt
    ) {
    }
}
