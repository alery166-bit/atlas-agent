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
 * Test-only LLM-search fixture. Its answer intentionally has no accessible
 * citation so the evidence gate can prove that it remains a lead.
 */
@Component
@Profile("test")
public class FixtureLlmSearchProvider implements PublicSearchProvider {
    private static final String PROVIDER = "fixture-llm-search";

    @Override
    public SearchBatch search(SearchRequest request) {
        if ("JSON样本企业有限公司".equals(request.companyName())
            && (request.targetRisk() == RiskType.WAGE_ARREARS
                || (request.targetRisk() == RiskType.OTHER
                    && "GENERAL_WEB".equals(request.sourceScope())))) {
            return SearchBatch.results(PROVIDER, List.of(new SearchResult(
                "fixture-llm-wage-1",
                "模型检索提示JSON样本企业有限公司可能存在欠薪",
                null,
                "模型回答未返回可访问原文引用，只能作为待补充线索。",
                Instant.parse("2026-07-29T03:00:00Z"),
                1,
                Map.of("fixture", "true", "citation_accessible", "false")
            )), request.requestedAt());
        }
        return SearchBatch.empty(PROVIDER, request.requestedAt());
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
            PROVIDER,
            ProviderCapabilities.ProviderMode.LLM_SEARCH,
            false,
            false
        );
    }
}
