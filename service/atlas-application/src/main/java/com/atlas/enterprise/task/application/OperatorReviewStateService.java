package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.application.SnapshotNotFoundException;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.intelligence.EvidenceContentReference;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.application.RiskScoreNotFoundException;
import com.atlas.enterprise.risk.port.OperatorDecisionRepository;
import com.atlas.enterprise.risk.port.RiskScoreSnapshotRepository;
import com.atlas.enterprise.task.OperatorReviewState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class OperatorReviewStateService {
    private final DataSnapshotRepository snapshots;
    private final RiskScoreSnapshotRepository scores;
    private final OperatorDecisionRepository decisions;
    private final PublicIntelligenceRepository publicIntelligence;

    public OperatorReviewStateService(
        DataSnapshotRepository snapshots,
        RiskScoreSnapshotRepository scores,
        OperatorDecisionRepository decisions,
        PublicIntelligenceRepository publicIntelligence
    ) {
        this.snapshots = snapshots;
        this.scores = scores;
        this.decisions = decisions;
        this.publicIntelligence = publicIntelligence;
    }

    public OperatorReviewState current(UUID taskId) {
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        RiskScoreSnapshot score = scores.findLatestByTaskId(taskId)
            .orElseThrow(() -> new RiskScoreNotFoundException(
                "No risk score has been calculated for task " + taskId,
                taskId
            ));
        return build(
            snapshot,
            score,
            decisions.findByTaskId(taskId),
            publicIntelligence.findEvidenceByTaskId(taskId),
            publicIntelligence.findContentReferencesByTaskId(taskId)
        );
    }

    public OperatorReviewState build(
        DataSnapshot snapshot,
        RiskScoreSnapshot score,
        java.util.List<OperatorDecision> taskDecisions,
        java.util.List<PublicEvidence> evidence,
        java.util.List<EvidenceContentReference> contentSnapshots
    ) {
        if (!score.dataSnapshotId().equals(snapshot.snapshotId())) {
            throw new OperatorConfirmationValidationException(
                "Latest risk score was not calculated from the latest data snapshot"
            );
        }
        Map<UUID, EvidenceContentReference> latestCaptured = contentSnapshots
            .stream()
            .filter(content -> content.status() == EvidenceContentStatus.CAPTURED)
            .collect(Collectors.toMap(
                EvidenceContentReference::evidenceId,
                Function.identity(),
                (left, right) -> left.capturedAt().isAfter(right.capturedAt())
                    ? left
                    : right
            ));

        StringBuilder canonical = new StringBuilder()
            .append(snapshot.snapshotId()).append('|')
            .append(snapshot.contentHash()).append('|')
            .append(score.scoreSnapshotId()).append('|')
            .append(score.inputHash()).append('|')
            .append(score.originalScore().toPlainString()).append('|')
            .append(score.manualScore().toPlainString()).append('|')
            .append(score.originalRiskLevel()).append('|')
            .append(score.manualRiskLevel());
        taskDecisions.stream()
            .sorted(Comparator.comparing(
                OperatorDecision::decisionId
            ))
            .forEach(decision -> canonical
                .append('|').append(decision.decisionId())
                .append('|').append(decision.afterJson())
                .append('|').append(decision.reasonCode())
                .append('|').append(decision.reasonText())
                .append('|').append(decision.operatorId())
                .append('|').append(decision.createdAt()));
        evidence.stream()
            .sorted(Comparator.comparing(PublicEvidence::evidenceId))
            .forEach(item -> {
                EvidenceContentReference content = latestCaptured.get(
                    item.evidenceId()
                );
                canonical
                    .append('|').append(item.evidenceId())
                    .append('|').append(item.verificationStatus())
                    .append('|').append(item.contentHash())
                    .append('|').append(content == null ? null : content.contentSnapshotId())
                    .append('|').append(content == null ? null : content.extractedTextHash())
                    .append('|').append(content != null && content.truncated());
            });

        return new OperatorReviewState(
            snapshot.snapshotId(),
            score.scoreSnapshotId(),
            sha256(canonical.toString()),
            count(evidence, EvidenceVerificationStatus.CONFIRMED),
            count(evidence, EvidenceVerificationStatus.REJECTED),
            count(evidence, EvidenceVerificationStatus.UNVERIFIED)
        );
    }

    private static int count(
        java.util.List<PublicEvidence> evidence,
        EvidenceVerificationStatus status
    ) {
        return Math.toIntExact(evidence.stream()
            .filter(item -> item.verificationStatus() == status)
            .count());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
