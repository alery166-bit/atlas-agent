package com.atlas.enterprise.task.application;

public record CreateTaskCommand(
    String prompt,
    String companyQuery,
    String previousReportFileId,
    String operatorId,
    String idempotencyKey
) {
}
