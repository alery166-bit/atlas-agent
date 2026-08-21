package com.atlas.enterprise.task;

import java.util.UUID;

public record OperatorReviewState(
    UUID dataSnapshotId,
    UUID scoreSnapshotId,
    String stateHash,
    int confirmedEvidenceCount,
    int rejectedEvidenceCount,
    int unverifiedEvidenceCount
) {
    public OperatorReviewState {
        if (dataSnapshotId == null || scoreSnapshotId == null
            || stateHash == null || stateHash.isBlank()) {
            throw new IllegalArgumentException("operator review state is incomplete");
        }
        if (confirmedEvidenceCount < 0 || rejectedEvidenceCount < 0
            || unverifiedEvidenceCount < 0) {
            throw new IllegalArgumentException("evidence counts must not be negative");
        }
    }
}
