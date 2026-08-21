package com.atlas.enterprise.report.api;

import com.atlas.enterprise.report.port.PreviousReportUploadStore;
import com.atlas.enterprise.report.PreviousReport;

public record PreviousReportUploadResponse(
    String fileId,
    String originalFilename,
    long size,
    String contentSha256,
    PreviousReport parsedReport
) {
    static PreviousReportUploadResponse from(
        PreviousReportUploadStore.StoredPreviousReport stored,
        PreviousReport parsedReport
    ) {
        return new PreviousReportUploadResponse(
            stored.fileId(),
            stored.originalFilename(),
            stored.size(),
            stored.contentSha256(),
            parsedReport
        );
    }
}
