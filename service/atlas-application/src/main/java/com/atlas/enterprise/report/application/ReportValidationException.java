package com.atlas.enterprise.report.application;

public class ReportValidationException extends RuntimeException {
    public ReportValidationException(String message) {
        super(message);
    }

    public ReportValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
