package com.atlas.enterprise.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.enterprise.company.CompanyFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VersionedRiskScoreEngineTest {
    private final RiskScoreEngine engine = new VersionedRiskScoreEngine();

    @Test
    void usesMaximumEventFloorInsteadOfAddingFloors() {
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
        assertEquals(RiskLevel.HIGH, result.originalRiskLevel());
    }

    @Test
    void keepsHigherLegacyScoreAndProducesStableInputHash() {
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

        assertEquals(new BigDecimal("9.8500"), first.originalScore());
        assertEquals(first.inputHash(), second.inputHash());
    }

    @Test
    void manualAdjustmentDoesNotOverwriteOriginalScore() {
        RiskScoreResult result = engine.calculate(request(
            BigDecimal.ZERO,
            List.of(event(RiskType.OUT_OF_CONTACT, "finding-1"))
        ));
        RiskScoreSnapshot original = RiskScoreSnapshot.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            result
        );
        UUID adjustedSnapshotId = UUID.randomUUID();
        RiskScoreSnapshot snapshot = original.adjustManualScore(
            adjustedSnapshotId,
            new BigDecimal("4.5"),
            Instant.parse("2026-08-19T04:00:00Z")
        );

        assertEquals(new BigDecimal("6"), original.manualScore());
        assertEquals(adjustedSnapshotId, snapshot.scoreSnapshotId());
        assertEquals(new BigDecimal("6"), snapshot.originalScore());
        assertEquals(new BigDecimal("4.5"), snapshot.manualScore());
        assertEquals(RiskLevel.MEDIUM, snapshot.manualRiskLevel());
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
                "测试企业有限公司",
                "91110101TEST000001",
                "110101000001",
                "张三",
                "存续",
                "北京市",
                "有限责任公司",
                "100 万元",
                "2020-01-01",
                "北京市市场监督管理局",
                "技术服务",
                "软件业",
                "TEST",
                "test-1",
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:00Z"),
                Map.of()
            ),
            events,
            legacyScore,
            VersionedRiskScoreEngine.RULE_VERSION,
            Instant.parse("2026-07-29T00:00:00Z")
        );
    }
}
