package com.atlas.enterprise.report.port;

public interface PreviousReportUploadStore {
    StoredPreviousReport store(String originalFilename, byte[] content);

    record StoredPreviousReport(
        String fileId,
        String originalFilename,
        long size,
        String contentSha256
    ) {
    }
}
