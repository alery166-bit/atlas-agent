package com.atlas.enterprise.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructuredRiskEventFloorExtractorTest {

    @Test
    void includesOnlyRecentStructuredEventsWithConfiguredFloors() {
        Instant calculatedAt = Instant.parse("2026-08-14T10:00:00Z");
        DataSnapshot snapshot = snapshot(List.of(
            event("WAGE_ARREARS", "wage-1", "2026-07-01"),
            event("STORE_CLOSURE", "closure-1", "2025-09-01"),
            event("OUT_OF_CONTACT", "old-contact", "2024-01-01"),
            event("ADMINISTRATIVE_PENALTY", "penalty-1", "2026-07-01"),
            event("WAGE_ARREARS", "missing-date", null)
        ));

        List<ConfirmedRiskEvent> result = StructuredRiskEventFloorExtractor.extract(
            snapshot,
            calculatedAt,
            RiskScoringPolicy.defaultPolicy()
        );

        assertEquals(2, result.size());
        assertEquals(RiskType.WAGE_ARREARS, result.get(0).riskType());
        assertEquals(RiskType.STORE_CLOSURE, result.get(1).riskType());
    }

    private static DataSnapshot snapshot(List<RiskEvent> events) {
        Instant now = Instant.parse("2026-08-14T09:00:00Z");
        CompanyFacts facts = new CompanyFacts(
            "评分样本企业有限公司", "91110000000000000X", "110000000000000",
            "测试法人", "存续", "北京市", "有限责任公司", "1000万元",
            "2020-01-01", "登记机关", "技术服务", "软件业", "TEST",
            "company-1", now, now, Map.of()
        );
        return new DataSnapshot(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            facts, List.of(), events, List.of(), "hash", now
        );
    }

    private static RiskEvent event(String type, String id, String occurredAt) {
        Instant now = Instant.parse("2026-08-14T09:00:00Z");
        return new RiskEvent(
            type, "ELASTICSEARCH", "risk-event", id, occurredAt,
            type, type, now, now, Map.of()
        );
    }
}
