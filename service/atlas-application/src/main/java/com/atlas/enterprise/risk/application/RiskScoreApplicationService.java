package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.application.SnapshotNotFoundException;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.configuration.application.SkillExecutionGate;
import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskAssessmentRevision;
import com.atlas.enterprise.risk.RiskAdjustmentReason;
import com.atlas.enterprise.risk.RiskScoreEngine;
import com.atlas.enterprise.risk.LegacyRiskFeatureExtractor;
import com.atlas.enterprise.risk.RiskScoreRequest;
import com.atlas.enterprise.risk.RiskScoreResult;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.RiskScoringPolicy;
import com.atlas.enterprise.risk.RiskType;
import com.atlas.enterprise.risk.StructuredRiskEventFloorExtractor;
import com.atlas.enterprise.risk.port.OperatorDecisionRepository;
import com.atlas.enterprise.risk.port.RiskAssessmentRevisionRepository;
import com.atlas.enterprise.risk.port.RiskScoreSnapshotRepository;
import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskScoreApplicationService {
    private static final List<String> LEGACY_SCORE_KEYS = List.of(
        "risk_score",
        "riskScore",
        "legacyScore"
    );

    private final RiskScoreEngine engine;
    private final RiskScoreSnapshotRepository scores;
    private final RiskAssessmentRevisionRepository assessmentRevisions;
    private final OperatorDecisionRepository decisions;
    private final DataSnapshotRepository snapshots;
    private final TaskRepository tasks;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;
    private final RiskRulePolicyResolver policyResolver;
    private final SkillExecutionGate skillGate;

    public RiskScoreApplicationService(
        RiskScoreEngine engine,
        RiskScoreSnapshotRepository scores,
        RiskAssessmentRevisionRepository assessmentRevisions,
        OperatorDecisionRepository decisions,
        DataSnapshotRepository snapshots,
        TaskRepository tasks,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        Clock clock,
        RiskRulePolicyResolver policyResolver,
        SkillExecutionGate skillGate
    ) {
        this.engine = engine;
        this.scores = scores;
        this.assessmentRevisions = assessmentRevisions;
        this.decisions = decisions;
        this.snapshots = snapshots;
        this.tasks = tasks;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.policyResolver = policyResolver;
        this.skillGate = skillGate;
    }

    @Transactional
    public RiskScoreSnapshot calculate(
        UUID taskId,
        List<ConfirmedRiskEvent> confirmedRiskEvents
    ) {
        requireTask(taskId);
        skillGate.requireEnabled(taskId, "risk.score");
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        if (snapshot.sourceStatuses().stream().anyMatch(status -> status.failed())) {
            throw new RiskScoreValidationException(
                "Risk score cannot be calculated while a required source has failed"
            );
        }

        RiskScoreResult result;
        List<ConfirmedRiskEvent> effectiveRiskEvents;
        try {
            Instant calculatedAt = clock.instant();
            RiskScoringPolicy policy = policyResolver.resolve(taskId);
            effectiveRiskEvents = mergeRiskEvents(
                confirmedRiskEvents,
                StructuredRiskEventFloorExtractor.extract(snapshot, calculatedAt, policy)
            );
            result = engine.calculate(new RiskScoreRequest(
                taskId,
                snapshot.snapshotId(),
                snapshot.companyFacts(),
                effectiveRiskEvents,
                legacyScore(snapshot.companyFacts().additionalFields()),
                LegacyRiskFeatureExtractor.extract(snapshot, calculatedAt, policy),
                policy,
                policy.version(),
                calculatedAt
            ));
        } catch (IllegalArgumentException exception) {
            throw new RiskScoreValidationException(exception.getMessage(), exception);
        }

        RiskScoreSnapshot existing = scores.findByInputHash(taskId, result.inputHash())
            .orElse(null);
        if (existing != null) {
            ensureSystemRevision(existing, snapshot, effectiveRiskEvents);
            return existing;
        }
        RiskScoreSnapshot saved = scores.save(RiskScoreSnapshot.create(
            UUID.randomUUID(),
            taskId,
            snapshot.snapshotId(),
            result
        ));
        RiskAssessmentRevision assessmentRevision = ensureSystemRevision(
            saved,
            snapshot,
            effectiveRiskEvents
        );
        publish(
            taskId,
            "risk.score.calculated",
            Map.of(
                "scoreSnapshotId", saved.scoreSnapshotId().toString(),
                "originalScore", saved.originalScore().toPlainString(),
                "manualScore", saved.manualScore().toPlainString(),
                "riskLevel", saved.manualRiskLevel().name(),
                "ruleVersion", saved.ruleVersion(),
                "assessmentRevisionId",
                    assessmentRevision.assessmentRevisionId().toString(),
                "assessmentRevisionNo",
                    Integer.toString(assessmentRevision.revisionNo())
            ),
            saved.calculatedAt()
        );
        return saved;
    }

    @Transactional(readOnly = true)
    public RiskScoreSnapshot latest(UUID taskId) {
        requireTask(taskId);
        return scores.findLatestByTaskId(taskId)
            .orElseThrow(() -> new RiskScoreNotFoundException(
                "No risk score has been calculated for task " + taskId,
                taskId
            ));
    }

    @Transactional
    public RiskScoreAdjustmentResult adjust(
        UUID taskId,
        UUID scoreSnapshotId,
        BigDecimal manualScore,
        RiskAdjustmentReason reasonCode,
        String reasonText,
        String operatorId
    ) {
        requireTask(taskId);
        validateAdjustment(manualScore, reasonCode, reasonText, operatorId);
        RiskScoreSnapshot before = scores.findById(scoreSnapshotId)
            .orElseThrow(() -> new RiskScoreNotFoundException(scoreSnapshotId));
        if (!before.taskId().equals(taskId)) {
            throw new RiskScoreValidationException(
                "Risk score snapshot does not belong to task " + taskId
            );
        }

        Instant now = clock.instant();
        RiskScoreSnapshot after;
        try {
            after = before.adjustManualScore(UUID.randomUUID(), manualScore, now);
        } catch (IllegalArgumentException exception) {
            throw new RiskScoreValidationException(exception.getMessage(), exception);
        }
        scores.save(after);

        RiskAssessmentRevision previousRevision = assessmentRevisions
            .findSystemRevisionByScoreSnapshotId(scoreSnapshotId)
            .orElseGet(() -> ensureSystemRevision(
                before,
                snapshots.findById(before.dataSnapshotId())
                    .orElseThrow(() -> new SnapshotNotFoundException(taskId)),
                List.of()
            ));
        RiskAssessmentRevision manualRevision = assessmentRevisions.save(
            RiskAssessmentRevisionFactory.manual(
            after,
            previousRevision,
            assessmentRevisions.nextRevisionNo(taskId),
            operatorId.trim(),
            reasonCode.name(),
            reasonText.trim(),
            now
            )
        );
        OperatorDecision decision = decisions.save(new OperatorDecision(
            UUID.randomUUID(),
            taskId,
            "RISK_SCORE_SNAPSHOT",
            after.scoreSnapshotId(),
            "MANUAL_SCORE_ADJUSTMENT",
            scoreJson(before),
            scoreJson(after),
            reasonCode,
            reasonText.trim(),
            operatorId.trim(),
            now
        ));
        boolean floorOverrideWarning =
            manualScore.compareTo(before.eventFloorScore()) < 0;
        publish(
            taskId,
            "risk.score.adjusted",
            Map.ofEntries(
                Map.entry("scoreSnapshotId", after.scoreSnapshotId().toString()),
                Map.entry("previousScoreSnapshotId", scoreSnapshotId.toString()),
                Map.entry("adjustedScoreSnapshotId", after.scoreSnapshotId().toString()),
                Map.entry("decisionId", decision.decisionId().toString()),
                Map.entry("previousManualScore", before.manualScore().toPlainString()),
                Map.entry("manualScore", after.manualScore().toPlainString()),
                Map.entry("riskLevel", after.manualRiskLevel().name()),
                Map.entry("reasonCode", reasonCode.name()),
                Map.entry("operatorId", decision.operatorId()),
                Map.entry(
                    "assessmentRevisionId",
                    manualRevision.assessmentRevisionId().toString()
                ),
                Map.entry(
                    "assessmentRevisionNo",
                    Integer.toString(manualRevision.revisionNo())
                ),
                Map.entry("floorOverrideWarning", Boolean.toString(floorOverrideWarning))
            ),
            now
        );
        return new RiskScoreAdjustmentResult(
            after,
            decision,
            floorOverrideWarning
        );
    }

    @Transactional(readOnly = true)
    public List<OperatorDecision> decisions(UUID taskId) {
        requireTask(taskId);
        return decisions.findByTaskId(taskId);
    }

    @Transactional(readOnly = true)
    public List<RiskAssessmentRevision> assessmentHistory(UUID taskId) {
        requireTask(taskId);
        return assessmentRevisions.findByTaskId(taskId);
    }

    private RiskAssessmentRevision ensureSystemRevision(
        RiskScoreSnapshot score,
        DataSnapshot snapshot,
        List<ConfirmedRiskEvent> effectiveRiskEvents
    ) {
        return assessmentRevisions.findSystemRevisionByScoreSnapshotId(
                score.scoreSnapshotId()
            )
            .orElseGet(() -> assessmentRevisions.save(
                RiskAssessmentRevisionFactory.system(
                    score,
                    snapshot,
                    effectiveRiskEvents,
                    assessmentRevisions.nextRevisionNo(score.taskId())
                )
            ));
    }

    private void requireTask(UUID taskId) {
        if (tasks.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
    }

    static BigDecimal legacyScore(Map<String, String> fields) {
        for (String key : LEGACY_SCORE_KEYS) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) {
                try {
                    return new BigDecimal(value.trim());
                } catch (NumberFormatException exception) {
                    throw new RiskScoreValidationException(
                        "Invalid legacy score in company facts: " + value,
                        exception
                    );
                }
            }
        }
        return null;
    }

    static List<ConfirmedRiskEvent> mergeRiskEvents(
        List<ConfirmedRiskEvent> confirmedEvidence,
        List<ConfirmedRiskEvent> structuredEvents
    ) {
        Map<RiskType, EventReferences> merged = new LinkedHashMap<>();
        List<ConfirmedRiskEvent> inputs = new ArrayList<>();
        if (confirmedEvidence != null) {
            inputs.addAll(confirmedEvidence);
        }
        if (structuredEvents != null) {
            inputs.addAll(structuredEvents);
        }
        for (ConfirmedRiskEvent input : inputs) {
            EventReferences references = merged.computeIfAbsent(
                input.riskType(),
                ignored -> new EventReferences(input.referenceId(), input.title())
            );
            if (!input.referenceId().equals(references.primaryReference)) {
                references.add(input.referenceId());
            }
            input.evidenceIds().forEach(references::add);
        }
        return merged.entrySet().stream()
            .map(entry -> new ConfirmedRiskEvent(
                entry.getKey(),
                entry.getValue().primaryReference,
                entry.getValue().title,
                List.copyOf(entry.getValue().references)
            ))
            .toList();
    }

    private static final class EventReferences {
        private final String primaryReference;
        private final String title;
        private final List<String> references = new ArrayList<>();

        private EventReferences(String primaryReference, String title) {
            this.primaryReference = primaryReference;
            this.title = title;
        }

        private void add(String reference) {
            if (reference != null && !reference.isBlank() && !references.contains(reference)) {
                references.add(reference);
            }
        }
    }

    private static void validateAdjustment(
        BigDecimal manualScore,
        RiskAdjustmentReason reasonCode,
        String reasonText,
        String operatorId
    ) {
        if (manualScore == null
            || manualScore.compareTo(BigDecimal.ZERO) < 0
            || manualScore.compareTo(BigDecimal.TEN) > 0) {
            throw new RiskScoreValidationException("manualScore must be in [0,10]");
        }
        if (reasonCode == null) {
            throw new RiskScoreValidationException("reasonCode is required");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new RiskScoreValidationException("reasonText is required");
        }
        if (reasonCode == RiskAdjustmentReason.OTHER && reasonText.trim().length() < 10) {
            throw new RiskScoreValidationException(
                "reasonText must contain at least 10 characters when reasonCode is OTHER"
            );
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new RiskScoreValidationException("operatorId is required");
        }
    }

    private static String scoreJson(RiskScoreSnapshot snapshot) {
        return """
            {"manual_score":"%s","manual_risk_level":"%s"}
            """.formatted(
            snapshot.manualScore().toPlainString(),
            snapshot.manualRiskLevel().name()
        ).trim();
    }

    private void publish(
        UUID taskId,
        String type,
        Map<String, String> payload,
        Instant occurredAt
    ) {
        TaskEventRecord event = events.append(taskId, type, payload, occurredAt);
        eventPublisher.publish(event);
    }
}
