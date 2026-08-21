package com.atlas.enterprise.report;

public record ReportFieldChange(
    String field,
    String beforeValue,
    String afterValue
) {
}
