package com.atlas.enterprise.intelligence;

import java.time.Instant;
import java.util.UUID;

public record EvidenceContentReference(
    UUID contentSnapshotId,
    UUID taskId,
    UUID evidenceId,
    EvidenceContentStatus status,
    String rawContentHash,
    String extractedTextHash,
    boolean truncated,
    Instant capturedAt
) {
    public EvidenceContentReference {
        if (contentSnapshotId == null || taskId == null || evidenceId == null
            || status == null || capturedAt == null) {
            throw new IllegalArgumentException(
                "evidence content reference fields are required"
            );
        }
    }
}
