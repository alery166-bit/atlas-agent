package com.atlas.enterprise.intelligence.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.risk.RiskType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicSearchQueryPlannerTest {
    @Test
    void wrapsCanonicalCompanyNameAndKnownAliasesAsExactPhrases() {
        var queries = new PublicSearchQueryPlanner().plan(company());

        assertEquals(2, queries.size());
        assertTrue(queries.stream().allMatch(query -> {
            String value = query.query();
            return value.startsWith("(\"北京简熹和食品有限公司\" OR ")
                && value.contains("\"简熹和\"")
                && value.contains("\"简熹生活\"")
                && !value.contains("91110101JSON000001");
        }));
        assertTrue(queries.stream().allMatch(query -> query.targetRisk() == RiskType.OTHER));
        assertTrue(queries.stream().noneMatch(query ->
            query.query().contains("失联") || query.query().contains("欠薪") || query.query().contains("闭店")
        ));
        assertEquals("GENERAL_WEB", queries.getFirst().sourceScope());
        assertEquals(
            List.of("tousu.sina.com.cn", "finance.cnr.cn", "xfb365.com"),
            queries.get(1).includeDomains()
        );
    }

    @Test
    void rendersConfiguredTemplatesAndInfersTheirTargetRisk() {
        var queries = new PublicSearchQueryPlanner().plan(
            company(),
            List.of(),
            List.of("{企业名} 失联 联系不上", "经营异常 行政处罚")
        );

        assertEquals(2, queries.size());
        assertEquals(RiskType.OUT_OF_CONTACT, queries.getFirst().targetRisk());
        assertEquals("LEGACY_KEYWORD_QUERY", queries.getFirst().sourceScope());
        assertTrue(queries.getFirst().query().contains("\"简熹生活\""));
        assertEquals(RiskType.OTHER, queries.get(1).targetRisk());
        assertTrue(queries.get(1).query().endsWith("经营异常 行政处罚"));
    }

    private static CompanyFacts company() {
        return new CompanyFacts(
            "北京简熹和食品有限公司",
            "91110101JSON000001",
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            Map.of("shortName", "简熹和", "brands", "[\"简熹生活\"]")
        );
    }
}
