package com.atlas.enterprise.task;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TaskStatus {
    CREATED,
    RESOLVING_SUBJECT,
    WAITING_SUBJECT_CONFIRMATION,
    LOADING_PREVIOUS_REPORT,
    COLLECTING_STRUCTURED_DATA,
    SEARCHING_PUBLIC_INTELLIGENCE,
    CALCULATING_RISK,
    WAITING_SUBJECT_DATA_REVIEW,
    WAITING_OPERATOR_CONFIRMATION,
    GENERATING_REPORT,
    COMPLETED,
    SOURCE_FAILED,
    MODEL_FAILED,
    REPORT_FAILED,
    CANCELLED;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
        Map.entry(CREATED, EnumSet.of(RESOLVING_SUBJECT, CANCELLED)),
        Map.entry(RESOLVING_SUBJECT, EnumSet.of(
            WAITING_SUBJECT_CONFIRMATION,
            LOADING_PREVIOUS_REPORT,
            COLLECTING_STRUCTURED_DATA,
            SOURCE_FAILED
        )),
        Map.entry(WAITING_SUBJECT_CONFIRMATION, EnumSet.of(RESOLVING_SUBJECT, CANCELLED)),
        Map.entry(LOADING_PREVIOUS_REPORT, EnumSet.of(COLLECTING_STRUCTURED_DATA, SOURCE_FAILED)),
        Map.entry(COLLECTING_STRUCTURED_DATA, EnumSet.of(SEARCHING_PUBLIC_INTELLIGENCE, SOURCE_FAILED)),
        Map.entry(SEARCHING_PUBLIC_INTELLIGENCE, EnumSet.of(CALCULATING_RISK, SOURCE_FAILED)),
        Map.entry(CALCULATING_RISK, EnumSet.of(
            WAITING_SUBJECT_DATA_REVIEW,
            WAITING_OPERATOR_CONFIRMATION,
            MODEL_FAILED
        )),
        Map.entry(WAITING_SUBJECT_DATA_REVIEW, EnumSet.of(CALCULATING_RISK, CANCELLED)),
        Map.entry(WAITING_OPERATOR_CONFIRMATION, EnumSet.of(GENERATING_REPORT, CANCELLED)),
        Map.entry(GENERATING_REPORT, EnumSet.of(COMPLETED, REPORT_FAILED)),
        Map.entry(SOURCE_FAILED, EnumSet.of(
            RESOLVING_SUBJECT,
            LOADING_PREVIOUS_REPORT,
            COLLECTING_STRUCTURED_DATA,
            SEARCHING_PUBLIC_INTELLIGENCE
        )),
        Map.entry(MODEL_FAILED, EnumSet.of(CALCULATING_RISK)),
        Map.entry(REPORT_FAILED, EnumSet.of(GENERATING_REPORT)),
        Map.entry(COMPLETED, EnumSet.noneOf(TaskStatus.class)),
        Map.entry(CANCELLED, EnumSet.noneOf(TaskStatus.class))
    );

    public boolean canTransitionTo(TaskStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
