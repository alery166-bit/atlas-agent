package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.task.OperatorConfirmation;

public final class ReportReadinessEvaluator {
    private ReportReadinessEvaluator() {
    }

    public static boolean isCurrentGeneratedReport(
        ReportVersion report,
        DataSnapshot snapshot,
        RiskScoreSnapshot score,
        OperatorConfirmation confirmation,
        TaskWorkspaceView.ConfirmationState confirmationState
    ) {
        return report != null
            && report.status() == ReportStatus.GENERATED
            && snapshot != null
            && score != null
            && confirmation != null
            && confirmationState == TaskWorkspaceView.ConfirmationState.VALID
            && snapshot.snapshotId().equals(report.dataSnapshotId())
            && score.scoreSnapshotId().equals(report.scoreSnapshotId())
            && confirmation.confirmationId().equals(
                report.operatorConfirmationId()
            );
    }
}
