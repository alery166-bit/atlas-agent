package com.atlas.enterprise.intelligence;

import java.time.Instant;
import java.util.UUID;

public record EvidenceContentSnapshot(
    UUID contentSnapshotId,
    UUID taskId,
    UUID evidenceId,
    EvidenceContentStatus status,
    String requestedUrl,
    String finalUrl,
    Integer httpStatus,
    String contentType,
    String rawContent,
    String extractedText,
    String rawContentHash,
    String extractedTextHash,
    long byteLength,
    boolean truncated,
    String failureCode,
    String failureMessage,
    Instant capturedAt
) {
    public EvidenceContentSnapshot {
        if (contentSnapshotId == null || taskId == null || evidenceId == null) {
            throw new IllegalArgumentException(
                "content snapshot identifiers are required"
            );
        }
        new EvidenceContentCapture(
            status,
            requestedUrl,
            finalUrl,
            httpStatus,
            contentType,
            rawContent,
            extractedText,
            rawContentHash,
            extractedTextHash,
            byteLength,
            truncated,
            failureCode,
            failureMessage,
            capturedAt
        );
    }
}
