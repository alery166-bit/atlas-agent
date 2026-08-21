package com.atlas.enterprise.report.api;

import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.ReportDiff;
import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.report.ReportVersion;
import java.time.Instant;
import java.util.UUID;

public record ReportVersionResponse(
    UUID reportId,
    UUID taskId,
    UUID atlasCompanyId,
    String templateVersion,
    int reportVersionNo,
    ReportStatus status,
    String inputHash,
    String contentHash,
    String mimeType,
    Long fileSize,
    UUID dataSnapshotId,
    UUID scoreSnapshotId,
    UUID operatorConfirmationId,
    PreviousReport parsedPreviousReport,
    ReportDiff diff,
    String failureReason,
    Instant generatedAt,
    String generatedBy
) {
    public static ReportVersionResponse from(ReportVersion report) {
        return new ReportVersionResponse(
            report.reportId(),
            report.taskId(),
            report.atlasCompanyId(),
            report.templateVersion(),
            report.reportVersionNo(),
            report.status(),
            report.inputHash(),
            report.contentHash(),
            report.mimeType(),
            report.fileSize(),
            report.dataSnapshotId(),
            report.scoreSnapshotId(),
            report.operatorConfirmationId(),
            report.parsedPreviousReport(),
            report.diff(),
            report.failureReason(),
            report.generatedAt(),
            report.generatedBy()
        );
    }
}
