package com.atlas.enterprise.intelligence.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.intelligence.EntityMatchStatus;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.risk.RiskType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceNormalizerTest {
    private final EvidenceNormalizer normalizer = new EvidenceNormalizer();

    @Test
    void removesTrackingParametersAndFragments() {
        assertEquals(
            "https://example.com/news/1?a=1&b=2",
            normalizer.normalizeUrl(
                "HTTPS://Example.com:443/news/1?utm_source=x&b=2&a=1#section"
            )
        );
        assertNull(normalizer.normalizeUrl("not a url"));
    }

    @Test
    void requiresExactEntityAnchorAndClassifiesFocusedRisks() {
        assertEquals(
            EntityMatchStatus.MATCHED,
            normalizer.matchEntity(company(),
                "JSON样本企业有限公司被投诉",
                "员工反映欠薪"
            ).status()
        );
        assertEquals(
            EntityMatchStatus.MATCHED,
            normalizer.matchEntity(company(),
                "样本云多家门店闭店",
                "员工反映欠薪"
            ).status()
        );
        assertEquals(
            EntityMatchStatus.POSSIBLE_MATCH,
            normalizer.matchEntity(company(),
                "其他样本企业被投诉",
                "员工反映欠薪"
            ).status()
        );
        assertEquals(
            RiskType.WAGE_ARREARS,
            normalizer.classifyRisk(
                RiskType.WAGE_ARREARS,
                "员工投诉",
                "拖欠工资仍待解决"
            )
        );
    }

    @Test
    void excludesBackgroundProfilesButKeepsExplicitRiskPages() {
        assertTrue(normalizer.isBackgroundProfileWithoutRisk(
            "https://baike.baidu.com/item/sample",
            "JSON样本企业有限公司_百度百科",
            "公司简介、法定代表人和经营范围"
        ));
        assertTrue(normalizer.isBackgroundProfileWithoutRisk(
            "https://www.qcc.com/firm/abc.html",
            "JSON样本企业有限公司工商信息",
            "企业基本信息查询"
        ));
        assertFalse(normalizer.isBackgroundProfileWithoutRisk(
            "https://baike.baidu.com/item/sample",
            "JSON样本企业有限公司被行政处罚",
            "监管部门已作出处罚决定"
        ));
        assertFalse(normalizer.isBackgroundProfileWithoutRisk(
            "https://news.example.com/article/1",
            "JSON样本企业有限公司多家门店关闭",
            "消费者反映退款困难"
        ));
    }

    private static CompanyFacts company() {
        return new CompanyFacts(
            "JSON样本企业有限公司",
            "91110101JSON000001",
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            Map.of("shortName", "JSON样本", "brands", "[\"样本云\"]")
        );
    }
}
