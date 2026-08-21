package com.atlas.enterprise.report;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReportVersion(
    UUID reportId,
    UUID taskId,
    UUID atlasCompanyId,
    String templateVersion,
    int reportVersionNo,
    ReportStatus status,
    String previousReportUri,
    String generatedReportUri,
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
    public ReportVersion {
        Objects.requireNonNull(reportId);
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(atlasCompanyId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(dataSnapshotId);
        Objects.requireNonNull(scoreSnapshotId);
    }

    public ReportVersion generated(StoredReportObject object, Instant at) {
        return new ReportVersion(
            reportId, taskId, atlasCompanyId, templateVersion, reportVersionNo,
            ReportStatus.GENERATED, previousReportUri, object.uri(), inputHash,
            object.contentHash(), object.mimeType(), object.size(), dataSnapshotId,
            scoreSnapshotId, operatorConfirmationId, parsedPreviousReport, diff,
            null, at, generatedBy
        );
    }

    public ReportVersion failed(String reason, Instant at) {
        return new ReportVersion(
            reportId, taskId, atlasCompanyId, templateVersion, reportVersionNo,
            ReportStatus.FAILED, previousReportUri, null, inputHash, null, null,
            null, dataSnapshotId, scoreSnapshotId, operatorConfirmationId,
            parsedPreviousReport, diff, reason, at, generatedBy
        );
    }

    public ReportVersion retrying() {
        if (status != ReportStatus.FAILED) {
            throw new IllegalStateException("Only a failed report can be retried");
        }
        return new ReportVersion(
            reportId, taskId, atlasCompanyId, templateVersion, reportVersionNo,
            ReportStatus.GENERATING, previousReportUri, null, inputHash, null, null,
            null, dataSnapshotId, scoreSnapshotId, operatorConfirmationId,
            parsedPreviousReport, diff, null, null, generatedBy
        );
    }
}
