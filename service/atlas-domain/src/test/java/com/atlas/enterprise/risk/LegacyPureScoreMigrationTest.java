package com.atlas.enterprise.risk;

import com.atlas.enterprise.company.CompanyFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPureScoreMigrationTest {
    private final LegacyRiskScoreEngineV1 engine = new LegacyRiskScoreEngineV1();

    @Test
    void calculatesLegacyBaseComponentsFromCompleteFeatures() {
        LegacyRiskFeatures features = features(
            LegacyScoringProfile.STANDARD,
            Set.of(),
            Set.of(),
            1, 1, 1, 1, 1, 1,
            false
        );

        RiskScoreResult result = engine.calculate(request(new BigDecimal("1.25"), features));

        assertDecimal("9.00", result.ruleCalculatedScore());
        assertTrue(result.ruleHits().stream().anyMatch(hit ->
            "LEGACY_NEGATIVE_SENTIMENT_KEYWORD".equals(hit.ruleCode())));
        assertTrue(result.ruleHits().stream().anyMatch(hit ->
            "LEGACY_ADMINISTRATIVE_PENALTY".equals(hit.ruleCode())));
        assertTrue(result.ruleHits().stream().noneMatch(hit ->
            "LEGACY_MATERIALIZED_SCORE".equals(hit.ruleCode())));
    }

    @Test
    void appliesTaskFrozenWeightAndEventFloorPolicy() {
        LegacyRiskFeatures features = features(
            LegacyScoringProfile.STANDARD,
            Set.of(),
            Set.of(),
            0, 0, 0, 0, 0, 0,
            false
        );
        features = new LegacyRiskFeatures(
            true, features.scoringProfile(), 1, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, Set.of(), Set.of(), false, false,
            null, null, "", false, BigDecimal.ZERO, BigDecimal.ZERO
        );
        RiskScoringPolicy policy = new RiskScoringPolicy(
            "risk.rules.v1/v2@test",
            Map.of(RiskType.STORE_CLOSURE, new BigDecimal("9")),
            Map.of("LEGACY_NEGATIVE_SENTIMENT", new BigDecimal("1.1")),
            Set.of(), 365, 180
        );
        RiskScoreRequest request = new RiskScoreRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
            companyFacts(),
            List.of(new ConfirmedRiskEvent(
                RiskType.STORE_CLOSURE, "closure-1", "门店关闭", List.of("evidence-1")
            )),
            BigDecimal.ZERO,
            features,
            policy,
            policy.version(),
            Instant.parse("2026-08-04T00:00:00Z")
        );

        RiskScoreResult result = engine.calculate(request);

        assertDecimal("1.10", result.ruleCalculatedScore());
        assertDecimal("9", result.eventFloorScore());
        assertDecimal("9", result.originalScore());
        assertEquals("risk.rules.v1/v2@test", result.ruleVersion());
    }

    @Test
    void keepsRegionalExceptionsExplicitAndAuditable() {
        Set<String> dishonest = Set.of("101102101");

        RiskScoreResult standard = engine.calculate(request(
            BigDecimal.ZERO,
            features(LegacyScoringProfile.STANDARD, Set.of(), dishonest,
                0, 0, 0, 0, 0, 0, false)
        ));
        RiskScoreResult chaoyang = engine.calculate(request(
            BigDecimal.ZERO,
            features(LegacyScoringProfile.CHAOYANG, Set.of(), dishonest,
                0, 0, 0, 0, 0, 0, false)
        ));
        RiskScoreResult xian = engine.calculate(request(
            BigDecimal.ZERO,
            features(LegacyScoringProfile.XIAN, Set.of(), dishonest,
                0, 0, 0, 0, 0, 0, false)
        ));

        assertDecimal("8.00", standard.ruleCalculatedScore());
        assertDecimal("6.00", chaoyang.ruleCalculatedScore());
        assertDecimal("4.50", xian.ruleCalculatedScore());
        assertTrue(chaoyang.ruleHits().stream().anyMatch(hit ->
            hit.references().contains("CHAOYANG")));
    }

    @Test
    void incompleteFeaturesContinueToUseMaterializedLegacyScore() {
        RiskScoreResult result = engine.calculate(request(
            new BigDecimal("7.35"),
            LegacyRiskFeatures.incomplete()
        ));

        assertDecimal("7.35", result.ruleCalculatedScore());
        assertTrue(result.ruleHits().stream().anyMatch(hit ->
            "LEGACY_MATERIALIZED_SCORE".equals(hit.ruleCode())));
    }

    @Test
    void incompleteFeaturesStillUseAvailableStructuredRiskSignals() {
        LegacyRiskFeatures partial = new LegacyRiskFeatures(
            false,
            LegacyScoringProfile.STANDARD,
            0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 0,
            Set.of(), Set.of(), false, false,
            null, null, "", false, BigDecimal.ZERO, BigDecimal.ZERO
        );

        RiskScoreResult result = engine.calculate(request(BigDecimal.ZERO, partial));

        assertDecimal("0.50", result.ruleCalculatedScore());
        assertTrue(result.ruleHits().stream().anyMatch(hit ->
            "LEGACY_EQUITY_FREEZE".equals(hit.ruleCode())));
    }

    @Test
    void sameCompleteFeaturesProduceStableHashRegardlessOfSetOrder() {
        LegacyRiskFeatures first = features(
            LegacyScoringProfile.STANDARD,
            new java.util.LinkedHashSet<>(List.of(8L, 15L)),
            new java.util.LinkedHashSet<>(List.of("103112104", "102105101")),
            0, 0, 0, 0, 0, 0, true
        );
        LegacyRiskFeatures second = features(
            LegacyScoringProfile.STANDARD,
            new java.util.LinkedHashSet<>(List.of(15L, 8L)),
            new java.util.LinkedHashSet<>(List.of("102105101", "103112104")),
            0, 0, 0, 0, 0, 0, true
        );

        assertEquals(
            engine.calculate(request(BigDecimal.ZERO, first)).inputHash(),
            engine.calculate(request(BigDecimal.ZERO, second)).inputHash()
        );
    }

    private static LegacyRiskFeatures features(
        LegacyScoringProfile profile,
        Set<Long> industries,
        Set<String> labels,
        int sentimentKeyword,
        int complaints,
        int judicialKeyword,
        int abnormal,
        int seriousIllegal,
        int penalty,
        boolean changed
    ) {
        return new LegacyRiskFeatures(
            true,
            profile,
            sentimentKeyword > 0 ? 1 : 0,
            sentimentKeyword,
            complaints,
            0,
            judicialKeyword > 0 ? 1 : 0,
            judicialKeyword,
            abnormal,
            seriousIllegal,
            penalty,
            0,
            0,
            0,
            industries,
            labels,
            changed,
            changed,
            null,
            null,
            "",
            false,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    private static RiskScoreRequest request(BigDecimal legacyScore, LegacyRiskFeatures features) {
        return new RiskScoreRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
            companyFacts(),
            List.of(),
            legacyScore,
            features,
            LegacyRiskScoreEngineV1.RULE_VERSION,
            Instant.parse("2026-08-04T00:00:00Z")
        );
    }

    private static CompanyFacts companyFacts() {
        return new CompanyFacts(
            "迁移测试企业",
            "91110000000000000X",
            "registration",
            "测试法人",
            "存续",
            "测试地址",
            "有限责任公司",
            "100 万元",
            "2020-01-01",
            "测试登记机关",
            "测试经营范围",
            "测试行业",
            "TEST",
            "migration-test",
            Instant.parse("2026-08-03T00:00:00Z"),
            Instant.parse("2026-08-04T00:00:00Z"),
            Map.of()
        );
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
