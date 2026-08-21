package com.atlas.enterprise.report.application;

import java.util.UUID;

public class ReportNotFoundException extends RuntimeException {
    private final UUID reportId;

    public ReportNotFoundException(UUID reportId) {
        super("Report not found: " + reportId);
        this.reportId = reportId;
    }

    public UUID reportId() {
        return reportId;
    }
}
