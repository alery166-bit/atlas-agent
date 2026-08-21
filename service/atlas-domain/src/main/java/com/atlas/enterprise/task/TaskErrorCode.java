package com.atlas.enterprise.task;

public enum TaskErrorCode {
    SUBJECT_NOT_FOUND(false),
    SUBJECT_AMBIGUOUS(false),
    PREVIOUS_REPORT_REQUIRED(false),
    PREVIOUS_REPORT_UNSUPPORTED(false),
    PREVIOUS_REPORT_PARSE_FAILED(true),
    STRUCTURED_SOURCE_UNAVAILABLE(true),
    STRUCTURED_SOURCE_QUERY_FAILED(true),
    SEARCH_PROVIDER_UNAVAILABLE(true),
    MODEL_TIMEOUT(true),
    MODEL_OUTPUT_INVALID(true),
    RISK_SCORE_FAILED(true),
    OPERATOR_CONFIRMATION_REQUIRED(false),
    REPORT_TEMPLATE_INVALID(false),
    REPORT_GENERATION_FAILED(true),
    TASK_STATE_CONFLICT(false);

    private final boolean retryable;

    TaskErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
