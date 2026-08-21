package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.risk.LegacyRiskFeatureExtractor;
import com.atlas.enterprise.risk.RiskAssessmentLabel;
import com.atlas.enterprise.risk.RiskAssessmentRevision;
import com.atlas.enterprise.risk.RiskAssessmentTrigger;
import com.atlas.enterprise.risk.RiskRuleHit;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.RiskType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RiskAssessmentRevisionFactory {
    private RiskAssessmentRevisionFactory() {
    }

    static RiskAssessmentRevision system(
        RiskScoreSnapshot score,
        DataSnapshot snapshot,
        List<ConfirmedRiskEvent> effectiveEvents,
        int revisionNo
    ) {
        List<RiskAssessmentLabel> sourceLabels = sourceLabels(score, snapshot);
        List<RiskAssessmentLabel> ruleLabels = ruleLabels(score.ruleHits());
        List<RiskAssessmentLabel> finalLabels = finalLabels(
            sourceLabels,
            ruleLabels,
            effectiveEvents
        );
        return revision(
            score,
            revisionNo,
            RiskAssessmentTrigger.SYSTEM_CALCULATION,
            sourceLabels,
            ruleLabels,
            List.of(),
            finalLabels,
            score.originalScore(),
            score.originalRiskLevel(),
            "ATLAS_AGENT",
            "atlas-risk-engine",
            "AUTOMATIC_ASSESSMENT",
            "基于冻结数据、已确认事件和当前规则自动研判",
            score.calculatedAt()
        );
    }

    static RiskAssessmentRevision manual(
        RiskScoreSnapshot score,
        RiskAssessmentRevision previous,
        int revisionNo,
        String operatorId,
        String reasonCode,
        String reasonText,
        Instant createdAt
    ) {
        return revision(
            score,
            revisionNo,
            RiskAssessmentTrigger.MANUAL_SCORE_ADJUSTMENT,
            previous.sourceLabels(),
            previous.ruleLabels(),
            previous.modelLabels(),
            previous.finalLabels(),
            score.manualScore(),
            score.manualRiskLevel(),
            "OPERATOR",
            operatorId,
            reasonCode,
            reasonText,
            createdAt
        );
    }

    private static RiskAssessmentRevision revision(
        RiskScoreSnapshot score,
        int revisionNo,
        RiskAssessmentTrigger trigger,
        List<RiskAssessmentLabel> sourceLabels,
        List<RiskAssessmentLabel> ruleLabels,
        List<RiskAssessmentLabel> modelLabels,
        List<RiskAssessmentLabel> finalLabels,
        BigDecimal finalScore,
        com.atlas.enterprise.risk.RiskLevel finalRiskLevel,
        String actorType,
        String actorId,
        String reasonCode,
        String reasonText,
        Instant createdAt
    ) {
        return new RiskAssessmentRevision(
            UUID.randomUUID(), score.taskId(), score.scoreSnapshotId(),
            score.dataSnapshotId(), revisionNo, trigger,
            score.legacyScore(), score.ruleCalculatedScore(), score.eventFloorScore(),
            score.originalScore(), finalScore, score.originalRiskLevel(),
            finalRiskLevel, score.ruleVersion(), score.engineVersion(),
            sourceLabels, ruleLabels, modelLabels, finalLabels,
            actorType, actorId, reasonCode, reasonText, createdAt
        );
    }

    private static List<RiskAssessmentLabel> sourceLabels(
        RiskScoreSnapshot score,
        DataSnapshot snapshot
    ) {
        return LegacyRiskFeatureExtractor.extract(snapshot, score.calculatedAt())
            .riskLabelNos().stream().sorted().map(labelNo -> {
                RiskType type = RiskType.fromLegacyLabelNo(labelNo).orElse(RiskType.OTHER);
                String name = type == RiskType.OTHER ? labelNo : type.displayName();
                return new RiskAssessmentLabel(
                    labelNo, name, type, "ES_LEGACY_LABEL", null, null,
                    List.of(snapshot.snapshotId().toString())
                );
            }).toList();
    }

    private static List<RiskAssessmentLabel> ruleLabels(List<RiskRuleHit> hits) {
        Map<String, RiskAssessmentLabel> labels = new LinkedHashMap<>();
        for (RiskRuleHit hit : hits) {
            if (hit.riskType() == null || hit.riskType() == RiskType.OTHER) {
                continue;
            }
            RiskType type = hit.riskType();
            labels.putIfAbsent(type.name(), new RiskAssessmentLabel(
                type.legacyLabelNo(), type.displayName(), type,
                "SCORING_RULE:" + hit.ruleCode(), hit.score(), null,
                hit.references()
            ));
        }
        return List.copyOf(labels.values());
    }

    private static List<RiskAssessmentLabel> finalLabels(
        List<RiskAssessmentLabel> sourceLabels,
        List<RiskAssessmentLabel> ruleLabels,
        List<ConfirmedRiskEvent> events
    ) {
        Map<String, RiskAssessmentLabel> labels = new LinkedHashMap<>();
        for (ConfirmedRiskEvent event : events == null ? List.<ConfirmedRiskEvent>of() : events) {
            RiskType type = event.riskType();
            List<String> references = new ArrayList<>();
            references.add(event.referenceId());
            references.addAll(event.evidenceIds());
            labels.put(key(type.legacyLabelNo(), type), new RiskAssessmentLabel(
                type.legacyLabelNo(), type.displayName(), type,
                "CONFIRMED_RISK_EVENT", null, BigDecimal.ONE, references
            ));
        }
        for (RiskAssessmentLabel label : ruleLabels) {
            labels.putIfAbsent(key(label.labelCode(), label.riskType()), label);
        }
        for (RiskAssessmentLabel label : sourceLabels) {
            labels.putIfAbsent(key(label.labelCode(), label.riskType()), label);
        }
        return List.copyOf(labels.values());
    }

    private static String key(String code, RiskType type) {
        return code == null || code.isBlank() ? type.name() : code;
    }
}
