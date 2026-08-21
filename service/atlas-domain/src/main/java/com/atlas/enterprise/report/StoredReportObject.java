package com.atlas.enterprise.report;

public record StoredReportObject(
    String uri,
    String contentHash,
    long size,
    String mimeType
) {
}
