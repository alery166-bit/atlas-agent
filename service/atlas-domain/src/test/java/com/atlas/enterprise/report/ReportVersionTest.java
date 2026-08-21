package com.atlas.enterprise.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportVersionTest {
    @Test
    void retriesTheSameFailedVersionWithoutChangingItsIdempotencyKey() {
        UUID reportId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        ReportVersion failed = new ReportVersion(
            reportId,
            taskId,
            UUID.randomUUID(),
            "DOCX_V1",
            3,
            ReportStatus.GENERATING,
            "previous.docx",
            null,
            "same-input-hash",
            null,
            null,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            "operator"
        ).failed("renderer failed", Instant.parse("2026-08-11T08:00:00Z"));

        ReportVersion retrying = failed.retrying();

        assertEquals(reportId, retrying.reportId());
        assertEquals(taskId, retrying.taskId());
        assertEquals(3, retrying.reportVersionNo());
        assertEquals("same-input-hash", retrying.inputHash());
        assertEquals(ReportStatus.GENERATING, retrying.status());
        assertNull(retrying.failureReason());
        assertNull(retrying.generatedAt());
    }

    @Test
    void refusesToRetryANonFailedReport() {
        ReportVersion generating = new ReportVersion(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DOCX_V1", 1,
            ReportStatus.GENERATING, "previous.docx", null, "input", null, null,
            null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
            null, null, null, "operator"
        );

        assertThrows(IllegalStateException.class, generating::retrying);
    }
}
