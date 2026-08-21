package com.atlas.enterprise.report.application;

import com.atlas.enterprise.report.ReportVersion;

public record ReportDownload(
    ReportVersion report,
    String filename,
    byte[] content
) {
}
