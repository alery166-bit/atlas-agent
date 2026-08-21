package com.atlas.enterprise.task;

import java.time.Instant;
import java.util.UUID;

public record OperatorConfirmation(
    UUID confirmationId,
    UUID taskId,
    UUID dataSnapshotId,
    UUID scoreSnapshotId,
    String reviewStateHash,
    int confirmedEvidenceCount,
    int rejectedEvidenceCount,
    String operatorId,
    String note,
    Instant confirmedAt
) {
    public OperatorConfirmation {
        if (confirmationId == null || taskId == null || dataSnapshotId == null
            || scoreSnapshotId == null || confirmedAt == null) {
            throw new IllegalArgumentException("operator confirmation identifiers are required");
        }
        if (reviewStateHash == null || reviewStateHash.isBlank()) {
            throw new IllegalArgumentException("reviewStateHash is required");
        }
        if (confirmedEvidenceCount < 0 || rejectedEvidenceCount < 0) {
            throw new IllegalArgumentException("evidence counts must not be negative");
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId is required");
        }
        operatorId = operatorId.trim();
        note = note == null ? "" : note.trim();
    }

    public boolean matches(OperatorReviewState state) {
        return dataSnapshotId.equals(state.dataSnapshotId())
            && scoreSnapshotId.equals(state.scoreSnapshotId())
            && reviewStateHash.equals(state.stateHash());
    }
}
