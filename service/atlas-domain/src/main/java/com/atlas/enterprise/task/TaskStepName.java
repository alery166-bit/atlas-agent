package com.atlas.enterprise.task;

public enum TaskStepName {
    RESOLVE_SUBJECT(10),
    VALIDATE_PREVIOUS_REPORT_REFERENCE(20),
    COLLECT_STRUCTURED_DATA(30),
    SEARCH_PUBLIC_INTELLIGENCE(40);

    private final int sequenceNo;

    TaskStepName(int sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public int sequenceNo() {
        return sequenceNo;
    }
}
