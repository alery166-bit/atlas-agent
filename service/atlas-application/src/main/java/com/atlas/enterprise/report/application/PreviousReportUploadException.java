package com.atlas.enterprise.report.application;

public class PreviousReportUploadException extends RuntimeException {
    public PreviousReportUploadException(String message) {
        super(message);
    }

    public PreviousReportUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
