package com.atlas.enterprise.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStatusTest {
    @Test
    void sourceFailureStopsTheHappyPathButAllowsRetry() {
        assertTrue(TaskStatus.COLLECTING_STRUCTURED_DATA.canTransitionTo(TaskStatus.SOURCE_FAILED));
        assertFalse(TaskStatus.SOURCE_FAILED.canTransitionTo(TaskStatus.CALCULATING_RISK));
        assertTrue(TaskStatus.SOURCE_FAILED.canTransitionTo(TaskStatus.COLLECTING_STRUCTURED_DATA));
    }

    @Test
    void completedTaskIsTerminal() {
        assertTrue(TaskStatus.COMPLETED.isTerminal());
        assertFalse(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.CREATED));
    }
}
