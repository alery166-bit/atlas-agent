package com.atlas.enterprise.report.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.task.TaskStatus;
import org.junit.jupiter.api.Test;

class ReportApplicationServiceStateTest {
    @Test
    void recoversOnlyAStuckGenerationWithAnExistingFailedReport() {
        assertTrue(ReportApplicationService.canStartGeneration(
            TaskStatus.GENERATING_REPORT,
            ReportStatus.FAILED
        ));
        assertFalse(ReportApplicationService.canStartGeneration(
            TaskStatus.GENERATING_REPORT,
            ReportStatus.GENERATING
        ));
        assertFalse(ReportApplicationService.canStartGeneration(
            TaskStatus.GENERATING_REPORT,
            null
        ));
    }

    @Test
    void keepsNormalEntryStatesAvailable() {
        assertTrue(ReportApplicationService.canStartGeneration(
            TaskStatus.WAITING_OPERATOR_CONFIRMATION,
            null
        ));
        assertTrue(ReportApplicationService.canStartGeneration(
            TaskStatus.REPORT_FAILED,
            ReportStatus.FAILED
        ));
        assertTrue(ReportApplicationService.canStartGeneration(
            TaskStatus.COMPLETED,
            ReportStatus.FAILED
        ));
    }
}
