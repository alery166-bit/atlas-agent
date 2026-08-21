package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.risk.RiskType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PublicSearchQueryPlanner {
    public static final List<SearchScope> DEFAULT_SOURCE_SCOPES = List.of(
        new SearchScope("GENERAL_WEB", "综合公开网页", List.of(), true, "general"),
        new SearchScope(
            "COMPLAINT_PLATFORMS",
            "投诉平台",
            List.of("tousu.sina.com.cn", "finance.cnr.cn", "xfb365.com"),
            true,
            "general"
        )
    );
    private final CompanyIdentityTerms identityTerms = new CompanyIdentityTerms();

    public List<PlannedQuery> plan(CompanyFacts company) {
        return plan(company, List.of(), List.of());
    }

    public List<PlannedQuery> plan(CompanyFacts company, List<CompanyAlias> aliases) {
        return plan(company, aliases, List.of());
    }

    public List<PlannedQuery> plan(
        CompanyFacts company,
        List<CompanyAlias> aliases,
        List<String> queryTemplates
    ) {
        return plan(company, aliases, queryTemplates, List.of());
    }

    public List<PlannedQuery> plan(
        CompanyFacts company,
        List<CompanyAlias> aliases,
        List<String> queryTemplates,
        List<SearchScope> sourceScopes
    ) {
        List<CompanyIdentityTerms.IdentityTerm> terms = identityTerms.searchable(company, aliases);
        if (terms.isEmpty()) {
            throw new PublicIntelligenceValidationException(
                "At least one confirmed company identity term is required for public search"
            );
        }
        String entityAnchor = terms.stream()
            .map(term -> "\"" + term.value().replace("\"", "") + "\"")
            .collect(Collectors.joining(" OR "));
        if (terms.size() > 1) entityAnchor = "(" + entityAnchor + ")";

        if (sourceScopes != null && !sourceScopes.isEmpty()) {
            String finalEntityAnchor = entityAnchor;
            return sourceScopes.stream()
                .map(scope -> planned(finalEntityAnchor, scope))
                .toList();
        }

        if (queryTemplates == null || queryTemplates.isEmpty()) {
            String finalEntityAnchor = entityAnchor;
            return DEFAULT_SOURCE_SCOPES.stream()
                .map(scope -> planned(finalEntityAnchor, scope))
                .toList();
        }

        Map<String, PlannedQuery> queries = new LinkedHashMap<>();
        for (String configured : queryTemplates) {
            if (configured == null || configured.isBlank()) continue;
            String template = configured.trim();
            String rendered = render(template, entityAnchor);
            queries.putIfAbsent(rendered, new PlannedQuery(
                rendered, inferRisk(template), "LEGACY_KEYWORD_QUERY",
                List.of(), false, "general"
            ));
        }
        if (queries.isEmpty()) {
            throw new PublicIntelligenceValidationException(
                "At least one non-empty public search query template is required"
            );
        }
        return List.copyOf(queries.values());
    }

    private static String render(String template, String entityAnchor) {
        String rendered = template;
        boolean hasIdentityPlaceholder = false;
        for (String placeholder : List.of(
            "{企业名}", "{企业全称}", "{企业简称}", "{品牌名}",
            "{company}", "{company_name}", "{brand}"
        )) {
            if (rendered.contains(placeholder)) {
                rendered = rendered.replace(placeholder, entityAnchor);
                hasIdentityPlaceholder = true;
            }
        }
        return hasIdentityPlaceholder ? rendered : entityAnchor + " " + rendered;
    }

    private static RiskType inferRisk(String template) {
        boolean outOfContact = containsAny(template, "失联", "联系不上", "无法联系", "无人接听");
        boolean wageArrears = containsAny(template, "拖欠工资", "欠薪", "讨薪", "工资未发");
        boolean storeClosure = containsAny(template, "闭店", "关店", "停业", "门店关闭");
        int matches = (outOfContact ? 1 : 0) + (wageArrears ? 1 : 0) + (storeClosure ? 1 : 0);
        if (matches != 1) return RiskType.OTHER;
        if (outOfContact) return RiskType.OUT_OF_CONTACT;
        if (wageArrears) return RiskType.WAGE_ARREARS;
        return RiskType.STORE_CLOSURE;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static PlannedQuery planned(String entityAnchor, SearchScope scope) {
        return new PlannedQuery(
            entityAnchor, RiskType.OTHER, scope.code(), scope.includeDomains(),
            scope.includeRawContent(), scope.topic()
        );
    }

    public record PlannedQuery(
        String query,
        RiskType targetRisk,
        String sourceScope,
        List<String> includeDomains,
        boolean includeRawContent,
        String topic
    ) {
        public PlannedQuery {
            includeDomains = includeDomains == null ? List.of() : List.copyOf(includeDomains);
        }
    }

    public record SearchScope(
        String code,
        String label,
        List<String> includeDomains,
        boolean includeRawContent,
        String topic
    ) {
        public SearchScope {
            includeDomains = includeDomains == null ? List.of() : List.copyOf(includeDomains);
            topic = topic == null || topic.isBlank() ? "general" : topic;
        }
    }
}
