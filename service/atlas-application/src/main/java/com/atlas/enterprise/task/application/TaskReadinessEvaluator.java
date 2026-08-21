package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.intelligence.EvidenceContentReference;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.RiskType;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.OperatorReviewState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TaskReadinessEvaluator {
    private TaskReadinessEvaluator() {
    }

    public static TaskWorkspaceView.EvidenceProgress evidenceProgress(
        List<PublicEvidence> evidence,
        List<EvidenceContentReference> contentSnapshots
    ) {
        Map<RiskType, Integer> byRiskType = new EnumMap<>(RiskType.class);
        int confirmed = 0;
        int rejected = 0;
        int unverified = 0;
        for (PublicEvidence item : evidence) {
            byRiskType.merge(item.riskType(), 1, Integer::sum);
            if (item.verificationStatus()
                == EvidenceVerificationStatus.CONFIRMED) {
                confirmed++;
            } else if (item.verificationStatus()
                == EvidenceVerificationStatus.REJECTED) {
                rejected++;
            } else {
                unverified++;
            }
        }

        Map<UUID, EvidenceContentReference> latestContent = new HashMap<>();
        contentSnapshots.forEach(candidate -> latestContent.merge(
            candidate.evidenceId(),
            candidate,
            (left, right) -> left.capturedAt().isAfter(right.capturedAt())
                ? left
                : right
        ));
        int captured = Math.toIntExact(latestContent.values().stream()
            .filter(item -> item.status() == EvidenceContentStatus.CAPTURED)
            .count());
        int failed = latestContent.size() - captured;
        return new TaskWorkspaceView.EvidenceProgress(
            evidence.size(),
            confirmed,
            rejected,
            unverified,
            captured,
            failed,
            byRiskType
        );
    }

    public static List<TaskWorkspaceView.ReadinessBlocker> readinessBlockers(
        DataSnapshot snapshot,
        RiskScoreSnapshot score,
        TaskWorkspaceView.EvidenceProgress progress,
        boolean subjectDataConflictResolved
    ) {
        List<TaskWorkspaceView.ReadinessBlocker> blockers = new ArrayList<>();
        if (snapshot == null) {
            blockers.add(
                TaskWorkspaceView.ReadinessBlocker.DATA_SNAPSHOT_MISSING
            );
        }
        if (score == null) {
            blockers.add(
                TaskWorkspaceView.ReadinessBlocker.RISK_SCORE_MISSING
            );
        }
        if (snapshot != null && score != null
            && !score.dataSnapshotId().equals(snapshot.snapshotId())) {
            blockers.add(
                TaskWorkspaceView.ReadinessBlocker
                    .RISK_SCORE_NOT_FROM_LATEST_DATA
            );
        }
        if (progress.unverified() > 0) {
            blockers.add(
                TaskWorkspaceView.ReadinessBlocker.UNVERIFIED_EVIDENCE
            );
        }
        if (!subjectDataConflictResolved
            && !SubjectDataConflictDetector.detect(snapshot).isEmpty()) {
            blockers.add(
                TaskWorkspaceView.ReadinessBlocker.SUBJECT_DATA_CONFLICT
            );
        }
        return List.copyOf(blockers);
    }

    public static boolean hasInputBlocker(
        List<TaskWorkspaceView.ReadinessBlocker> blockers
    ) {
        return blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.DATA_SNAPSHOT_MISSING
        ) || blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.RISK_SCORE_MISSING
        ) || blockers.contains(
            TaskWorkspaceView.ReadinessBlocker.RISK_SCORE_NOT_FROM_LATEST_DATA
        );
    }

    public static TaskWorkspaceView.ConfirmationState confirmationState(
        OperatorConfirmation latest,
        OperatorReviewState currentState
    ) {
        if (currentState == null) {
            return TaskWorkspaceView.ConfirmationState.NOT_READY;
        }
        if (latest == null) {
            return TaskWorkspaceView.ConfirmationState.PENDING;
        }
        return latest.matches(currentState)
            ? TaskWorkspaceView.ConfirmationState.VALID
            : TaskWorkspaceView.ConfirmationState.STALE;
    }
}
