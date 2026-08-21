package com.atlas.enterprise.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyRiskFeatureExtractorTest {

    @Test
    void countsEsOffsetDateTimeInsideRiskWindow() {
        Instant calculatedAt = Instant.parse("2026-08-06T10:00:00Z");
        DataSnapshot snapshot = snapshot(List.of(
            event("EQUITY_FREEZE", "2026-07-26T01:12:36+08:00"),
            event("EQUITY_FREEZE", "2025-07-01T01:12:36+08:00"),
            event("EQUITY_FREEZE", "2026-08-07T01:12:36+08:00")
        ));

        LegacyRiskFeatures features = LegacyRiskFeatureExtractor.extract(
            snapshot,
            calculatedAt
        );

        assertTrue(features.complete());
        assertEquals(1, features.equityFreezeCount());
    }

    @Test
    void doesNotTreatSourceUpdateTimeAsRecentEquityFreezeDate() {
        Instant calculatedAt = Instant.parse("2026-08-06T10:00:00Z");
        Instant sourceUpdatedAt = Instant.parse("2026-07-25T17:12:36Z");
        RiskEvent event = new RiskEvent(
            "EQUITY_FREEZE",
            "ES",
            "company_equity_freeze",
            "freeze-1",
            "2026-07-26T01:12:36+08:00",
            "股权冻结",
            "股权冻结",
            sourceUpdatedAt,
            calculatedAt,
            Map.of("documentNo", "（2021）京0105执38065号")
        );

        LegacyRiskFeatures features = LegacyRiskFeatureExtractor.extract(
            snapshot(List.of(event)),
            calculatedAt
        );

        assertEquals(0, features.equityFreezeCount());
    }

    @Test
    void normalizesDisplayLabelsAndAddsAuthoritativeRegistrationStatus() {
        Instant now = Instant.parse("2026-08-06T09:00:00Z");
        CompanyFacts facts = new CompanyFacts(
            "标签迁移企业",
            "91110000000000000X",
            "110000000000000",
            "测试法人",
            "吊销",
            "测试地址",
            "有限责任公司",
            "1000万元",
            "2020-01-01",
            "测试登记机关",
            "测试经营范围",
            "测试行业",
            "TEST",
            "company-labels",
            now,
            now,
            Map.of("legacyLabels", "[\"经营异常\",\"103112113\",\"行政处罚\"]")
        );
        DataSnapshot snapshot = new DataSnapshot(
            UUID.fromString("00000000-0000-0000-0000-000000000211"),
            UUID.fromString("00000000-0000-0000-0000-000000000212"),
            UUID.fromString("00000000-0000-0000-0000-000000000213"),
            1,
            facts,
            List.of(),
            List.of(),
            List.of(),
            "snapshot-labels",
            now
        );

        LegacyRiskFeatures features = LegacyRiskFeatureExtractor.extract(
            snapshot,
            Instant.parse("2026-08-06T10:00:00Z")
        );

        assertEquals(
            java.util.Set.of("102101101", "103112113", "102101102", "102102101"),
            features.riskLabelNos()
        );
    }

    private static DataSnapshot snapshot(List<RiskEvent> events) {
        Instant now = Instant.parse("2026-08-06T09:00:00Z");
        CompanyFacts facts = new CompanyFacts(
            "测试企业",
            "91110000000000000X",
            "110000000000000",
            "测试法人",
            "存续",
            "测试地址",
            "有限责任公司",
            "1000万元",
            "2020-01-01",
            "测试登记机关",
            "测试经营范围",
            "测试行业",
            "TEST",
            "company-1",
            now,
            now,
            Map.of("legacyFeatureCompleteness", "FULL")
        );
        return new DataSnapshot(
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
            UUID.fromString("00000000-0000-0000-0000-000000000202"),
            UUID.fromString("00000000-0000-0000-0000-000000000203"),
            1,
            facts,
            List.of(),
            events,
            List.of(),
            "snapshot-hash",
            now
        );
    }

    private static RiskEvent event(String type, String occurredAt) {
        Instant now = Instant.parse("2026-08-06T09:00:00Z");
        return new RiskEvent(
            type,
            "ES",
            "risk-index",
            type + occurredAt,
            occurredAt,
            type,
            type,
            now,
            now,
            Map.of()
        );
    }
}
