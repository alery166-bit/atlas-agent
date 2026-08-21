package com.atlas.enterprise.report.port;

import com.atlas.enterprise.report.PreviousReport;

public interface PreviousReportParser {
    PreviousReport parse(byte[] docx);
}
