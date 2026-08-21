package com.atlas.enterprise.risk.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.risk.RiskType;
import org.junit.jupiter.api.Test;

class RiskScoreApplicationServiceTest {

    @Test
    void readsLegacyScoreFromNormalizedEsAndOfflineAliases() {
        assertEquals(
            new BigDecimal("6.25"),
            RiskScoreApplicationService.legacyScore(Map.of("legacyScore", "6.25"))
        );
        assertEquals(
            new BigDecimal("4.5"),
            RiskScoreApplicationService.legacyScore(Map.of("riskScore", "4.5"))
        );
        assertEquals(
            new BigDecimal("3"),
            RiskScoreApplicationService.legacyScore(Map.of("risk_score", "3"))
        );
        assertNull(RiskScoreApplicationService.legacyScore(Map.of()));
    }

    @Test
    void mergesStructuredAndPublicFloorInputsByRiskTypeWithoutLosingReferences() {
        List<ConfirmedRiskEvent> result = RiskScoreApplicationService.mergeRiskEvents(
            List.of(new ConfirmedRiskEvent(
                RiskType.WAGE_ARREARS, "public-1", "公开证据", List.of("evidence-1")
            )),
            List.of(new ConfirmedRiskEvent(
                RiskType.WAGE_ARREARS, "structured-1", "结构化记录", List.of("structured-1")
            ))
        );

        assertEquals(1, result.size());
        assertEquals("public-1", result.getFirst().referenceId());
        assertEquals(List.of("evidence-1", "structured-1"), result.getFirst().evidenceIds());
    }
}
