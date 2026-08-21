package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class LegacyRiskScoreEngineV1 implements RiskScoreEngine {
    public static final String RULE_VERSION = "RISK_RULES_V1";
    public static final String ENGINE_VERSION = "atlas-risk-engine/1.2.1-hybrid";

    private final LegacyRiskScoreAdapter legacyAdapter;
    private final LegacyPureScoreCalculator pureCalculator;

    public LegacyRiskScoreEngineV1() {
        this(new LegacyRiskScoreAdapter());
    }

    LegacyRiskScoreEngineV1(LegacyRiskScoreAdapter legacyAdapter) {
        this.legacyAdapter = legacyAdapter;
        this.pureCalculator = new LegacyPureScoreCalculator();
    }

    @Override
    public RiskScoreResult calculate(RiskScoreRequest request) {
        LegacyRiskScoreAdapter.LegacyScoreBaseline baseline =
            legacyAdapter.adapt(request.legacyScore());
        List<RiskRuleHit> hits = new ArrayList<>();
        BigDecimal calculatedScore;
        if (request.legacyFeatures().complete()) {
            LegacyPureScoreCalculator.Calculation calculation =
                pureCalculator.calculate(request.legacyFeatures(), request.scoringPolicy());
            calculatedScore = calculation.score();
            hits.addAll(calculation.ruleHits());
        } else {
            LegacyPureScoreCalculator.Calculation partialCalculation =
                pureCalculator.calculate(request.legacyFeatures(), request.scoringPolicy());
            if (partialCalculation.score().compareTo(baseline.score()) > 0) {
                calculatedScore = partialCalculation.score();
                hits.addAll(partialCalculation.ruleHits());
                hits.add(new RiskRuleHit(
                    "LEGACY_PARTIAL_FEATURE_FALLBACK",
                    "结构化特征分高于旧评分基线",
                    null,
                    calculatedScore,
                    "SCORE_SELECTION",
                    List.of(
                        "legacy=" + baseline.score().toPlainString(),
                        "structured=" + partialCalculation.score().toPlainString()
                    )
                ));
            } else {
                calculatedScore = baseline.score();
                hits.addAll(baseline.ruleHits());
            }
        }
        BigDecimal floor = BigDecimal.ZERO;

        List<ConfirmedRiskEvent> events = request.confirmedRiskEvents().stream()
            .sorted(Comparator.comparing((ConfirmedRiskEvent event) -> event.riskType().name())
                .thenComparing(ConfirmedRiskEvent::referenceId))
            .toList();
        for (ConfirmedRiskEvent event : events) {
            BigDecimal eventFloor = request.scoringPolicy().floorFor(event.riskType());
            if (eventFloor.signum() > 0) {
                floor = floor.max(eventFloor);
                List<String> references = new ArrayList<>();
                references.add(event.referenceId());
                references.addAll(event.evidenceIds());
                hits.add(new RiskRuleHit(
                    "EVENT_FLOOR_" + event.riskType().name(),
                    event.riskType().displayName() + "关联分值",
                    event.riskType(),
                    eventFloor,
                    "EVENT_FLOOR",
                    references
                ));
            }
        }

        BigDecimal ruleScore = clamp(calculatedScore);
        BigDecimal original = clamp(ruleScore.max(floor));
        return new RiskScoreResult(
            request.legacyScore(),
            ruleScore,
            floor,
            original,
            original,
            RiskLevel.from(original),
            RiskLevel.from(original),
            request.ruleVersion(),
            ENGINE_VERSION,
            inputHash(request, events),
            hits,
            request.calculatedAt()
        );
    }

    @Override
    public String ruleVersion() {
        return RULE_VERSION;
    }

    @Override
    public String engineVersion() {
        return ENGINE_VERSION;
    }

    private static BigDecimal clamp(BigDecimal score) {
        return score.max(BigDecimal.ZERO).min(BigDecimal.TEN);
    }

    private static String inputHash(
        RiskScoreRequest request,
        List<ConfirmedRiskEvent> sortedEvents
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.dataSnapshotId().toString());
        append(canonical, request.companyFacts().canonicalName());
        append(canonical, request.companyFacts().unifiedCreditCode());
        append(canonical, request.legacyScore() == null ? null : request.legacyScore().toPlainString());
        append(canonical, request.ruleVersion());
        append(canonical, ENGINE_VERSION);
        appendPolicy(canonical, request.scoringPolicy());
        appendFeatures(canonical, request.legacyFeatures());
        for (ConfirmedRiskEvent event : sortedEvents) {
            append(canonical, event.riskType().name());
            append(canonical, event.referenceId());
            event.evidenceIds().stream().sorted().forEach(id -> append(canonical, id));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void appendPolicy(StringBuilder target, RiskScoringPolicy policy) {
        append(target, policy.version());
        policy.eventFloors().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> {
                append(target, entry.getKey().name());
                append(target, entry.getValue().toPlainString());
            });
        policy.ruleWeights().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> {
                append(target, entry.getKey());
                append(target, entry.getValue().toPlainString());
            });
        policy.disabledLegacyLabels().stream().sorted()
            .forEach(label -> append(target, label));
        append(target, Integer.toString(policy.riskEventWindowDays()));
        append(target, Integer.toString(policy.companyChangeWindowDays()));
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append('|');
    }

    private static void appendFeatures(StringBuilder target, LegacyRiskFeatures features) {
        append(target, Boolean.toString(features.complete()));
        append(target, features.scoringProfile().name());
        append(target, Integer.toString(features.sentimentCount()));
        append(target, Integer.toString(features.sentimentKeywordCount()));
        append(target, Integer.toString(features.complaintCount()));
        append(target, Integer.toString(features.complaintKeywordCount()));
        append(target, Integer.toString(features.judicialDefendantCount()));
        append(target, Integer.toString(features.judicialKeywordCount()));
        append(target, Integer.toString(features.businessAbnormalCount()));
        append(target, Integer.toString(features.seriousIllegalCount()));
        append(target, Integer.toString(features.administrativePenaltyCount()));
        append(target, Integer.toString(features.equityPledgeCount()));
        append(target, Integer.toString(features.equityFreezeCount()));
        append(target, Integer.toString(features.stockPledgeCount()));
        features.industryIds().stream().sorted()
            .forEach(value -> append(target, Long.toString(value)));
        features.riskLabelNos().stream().sorted().forEach(value -> append(target, value));
        append(target, Boolean.toString(features.corporateShareholdersChange()));
        append(target, Boolean.toString(features.corporateShareholdersAddressChange()));
        append(target, features.paidCapitalTenThousands() == null
            ? null : features.paidCapitalTenThousands().toPlainString());
        append(target, features.operatingYears() == null
            ? null : Integer.toString(features.operatingYears()));
        append(target, features.listingInfo());
        append(target, Boolean.toString(features.monitorCompany()));
        append(target, features.relatedShareholderScore().toPlainString());
        append(target, features.relatedInvestmentScore().toPlainString());
    }
}
