package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.intelligence.SearchResult;
import com.atlas.enterprise.intelligence.port.PublicSearchProvider;
import com.atlas.enterprise.risk.RiskType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic test fixture. It is intentionally unavailable outside the test
 * profile and must never be represented as a live public-search result.
 */
@Component
@Profile("test")
public class FixturePublicSearchProvider implements PublicSearchProvider {
    private static final String PROVIDER = "fixture-search";

    @Override
    public SearchBatch search(SearchRequest request) {
        if (!"JSON样本企业有限公司".equals(request.companyName())) {
            return SearchBatch.empty(PROVIDER, request.requestedAt());
        }
        if (request.targetRisk() == RiskType.OTHER) {
            if ("COMPLAINT_PLATFORMS".equals(request.sourceScope())) {
                return SearchBatch.results(PROVIDER, List.of(
                    new SearchResult(
                        "fixture-wage-1",
                        "JSON样本企业有限公司员工反映拖欠工资",
                        "https://example.test/complaints/json-company?utm_source=fixture",
                        "投诉内容提及JSON样本企业有限公司存在欠薪和讨薪情况，状态待人工核验。",
                        Instant.parse("2026-07-28T01:00:00Z"), 1, Map.of("fixture", "true")
                    ),
                    new SearchResult(
                        "fixture-wage-duplicate",
                        "JSON样本企业有限公司员工反映拖欠工资",
                        "https://example.test/complaints/json-company",
                        "投诉内容提及JSON样本企业有限公司存在欠薪和讨薪情况，状态待人工核验。",
                        Instant.parse("2026-07-28T01:00:00Z"), 2,
                        Map.of("fixture", "true", "duplicate", "true")
                    )
                ), request.requestedAt());
            }
            return SearchBatch.results(PROVIDER, List.of(
                new SearchResult(
                    "fixture-closure-1", "样本云门店关闭信息",
                    "https://example.test/stores/json-company-closure",
                    "公开页面显示样本云相关门店已经闭店，主体和时间待人工核验。",
                    "JSON样本企业有限公司发布门店公告：样本云相关门店已经闭店，后续安排以公告为准。",
                    Instant.parse("2026-07-27T02:00:00Z"), 1, Map.of("fixture", "true")
                ),
                new SearchResult(
                    "fixture-unrelated-1", "其他企业无法联系",
                    "https://example.test/unrelated-company",
                    "公开信息描述的是其他市场主体，与目标企业无明确关联。",
                    Instant.parse("2026-07-26T02:00:00Z"), 2,
                    Map.of("fixture", "true", "entity_noise", "true")
                )
            ), request.requestedAt());
        }
        if (request.targetRisk() == RiskType.WAGE_ARREARS) {
            return SearchBatch.results(PROVIDER, List.of(
                new SearchResult(
                    "fixture-wage-1",
                    "JSON样本企业有限公司员工反映拖欠工资",
                    "https://example.test/complaints/json-company?utm_source=fixture",
                    "投诉内容提及JSON样本企业有限公司存在欠薪和讨薪情况，状态待人工核验。",
                    Instant.parse("2026-07-28T01:00:00Z"),
                    1,
                    Map.of("fixture", "true")
                ),
                new SearchResult(
                    "fixture-wage-duplicate",
                    "JSON样本企业有限公司员工反映拖欠工资",
                    "https://example.test/complaints/json-company",
                    "投诉内容提及JSON样本企业有限公司存在欠薪和讨薪情况，状态待人工核验。",
                    Instant.parse("2026-07-28T01:00:00Z"),
                    2,
                    Map.of("fixture", "true", "duplicate", "true")
                )
            ), request.requestedAt());
        }
        if (request.targetRisk() == RiskType.STORE_CLOSURE) {
            return SearchBatch.results(PROVIDER, List.of(
                new SearchResult(
                    "fixture-closure-1",
                    "样本云门店关闭信息",
                    "https://example.test/stores/json-company-closure",
                    "公开页面显示样本云相关门店已经闭店，主体和时间待人工核验。",
                    "JSON样本企业有限公司发布门店公告：样本云相关门店已经闭店，后续安排以公告为准。",
                    Instant.parse("2026-07-27T02:00:00Z"),
                    1,
                    Map.of("fixture", "true")
                )
            ), request.requestedAt());
        }
        if (request.targetRisk() == RiskType.OUT_OF_CONTACT) {
            return SearchBatch.results(PROVIDER, List.of(new SearchResult(
                "fixture-unrelated-1",
                "其他企业无法联系",
                "https://example.test/unrelated-company",
                "公开信息描述的是其他市场主体，与目标企业无明确关联。",
                Instant.parse("2026-07-26T02:00:00Z"),
                1,
                Map.of("fixture", "true", "entity_noise", "true")
            )), request.requestedAt());
        }
        return SearchBatch.empty(PROVIDER, request.requestedAt());
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
            PROVIDER,
            ProviderCapabilities.ProviderMode.FIXTURE,
            true,
            true
        );
    }
}
