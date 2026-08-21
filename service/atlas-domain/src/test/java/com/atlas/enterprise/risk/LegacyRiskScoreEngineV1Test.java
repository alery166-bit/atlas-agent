package com.atlas.enterprise.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.enterprise.company.CompanyFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyRiskScoreEngineV1Test {
    private final RiskScoreEngine engine = new LegacyRiskScoreEngineV1();

    @Test
    void keepsHigherLegacyScoreAndUsesEventFloor() {
        RiskScoreResult result = engine.calculate(request(
            new BigDecimal("3.25"),
            List.of(
                event(RiskType.OUT_OF_CONTACT, "finding-1"),
                event(RiskType.WAGE_ARREARS, "finding-2"),
                event(RiskType.STORE_CLOSURE, "finding-3")
            )
        ));

        assertEquals(new BigDecimal("3.25"), result.ruleCalculatedScore());
        assertEquals(new BigDecimal("8"), result.eventFloorScore());
        assertEquals(new BigDecimal("8"), result.originalScore());
        assertEquals(new BigDecimal("8"), result.manualScore());
        assertEquals(RiskLevel.HIGH, result.originalRiskLevel());
    }

    @Test
    void keepsStableInputHashForSameInput() {
        RiskScoreRequest request = request(
            new BigDecimal("9.8500"),
            List.of(event(RiskType.WAGE_ARREARS, "finding-2"))
        );
        RiskScoreResult first = engine.calculate(request);
        RiskScoreResult second = engine.calculate(new RiskScoreRequest(
            request.taskId(),
            request.dataSnapshotId(),
            request.companyFacts(),
            request.confirmedRiskEvents(),
            request.legacyScore(),
            request.ruleVersion(),
            request.calculatedAt().plusSeconds(30)
        ));

        assertEquals(first.originalScore(), second.originalScore());
        assertEquals(first.inputHash(), second.inputHash());
    }

    @Test
    void exposesLegacyEngineVersionMetadata() {
        RiskScoreResult result = engine.calculate(request(BigDecimal.ZERO, List.of()));
        assertEquals(LegacyRiskScoreEngineV1.RULE_VERSION, result.ruleVersion());
        assertEquals(LegacyRiskScoreEngineV1.ENGINE_VERSION, result.engineVersion());
    }

    private static ConfirmedRiskEvent event(RiskType type, String id) {
        return new ConfirmedRiskEvent(type, id, type.displayName(), List.of("evidence-" + id));
    }

    private static RiskScoreRequest request(
        BigDecimal legacyScore,
        List<ConfirmedRiskEvent> events
    ) {
        return new RiskScoreRequest(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            new CompanyFacts(
                "测试企业示例",
                "91110101TEST000002",
                "110101000002",
                "测试地址",
                "上海",
                "测试行业",
                "风险平台",
                "10000",
                "2020-01-01",
                "测试法人",
                "测试经营范围",
                "测试主体类型",
                "TEST",
                "test-2",
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:00Z"),
                Map.of()
            ),
            events,
            legacyScore,
            LegacyRiskScoreEngineV1.RULE_VERSION,
            Instant.parse("2026-07-30T00:00:00Z")
        );
    }
}
