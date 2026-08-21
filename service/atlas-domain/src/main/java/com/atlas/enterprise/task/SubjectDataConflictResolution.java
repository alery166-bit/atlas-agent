package com.atlas.enterprise.task;

import java.time.Instant;
import java.util.UUID;

public record SubjectDataConflictResolution(
    UUID resolutionId,
    UUID taskId,
    UUID dataSnapshotId,
    SubjectDataConflictDecision decision,
    String note,
    String operatorId,
    Instant resolvedAt
) {
    public SubjectDataConflictResolution {
        if (resolutionId == null || taskId == null || dataSnapshotId == null) {
            throw new IllegalArgumentException("subject data conflict resolution identifiers are required");
        }
        if (decision == null) {
            throw new IllegalArgumentException("subject data conflict decision is required");
        }
        note = requireText(note, "note");
        operatorId = requireText(operatorId, "operatorId");
        if (resolvedAt == null) {
            throw new IllegalArgumentException("resolvedAt is required");
        }
    }

    public boolean matches(UUID snapshotId) {
        return dataSnapshotId.equals(snapshotId);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
