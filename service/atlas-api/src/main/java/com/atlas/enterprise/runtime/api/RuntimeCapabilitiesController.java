package com.atlas.enterprise.runtime.api;

import com.atlas.enterprise.agent.port.AgentIntentModel;
import com.atlas.enterprise.company.port.CompanyDataProvider;
import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.port.PublicSearchProviderRegistry;
import com.atlas.enterprise.risk.RiskScoreEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeCapabilitiesController {
    private final CompanyDataProvider companyDataProvider;
    private final PublicSearchProviderRegistry searchProviders;
    private final List<AgentIntentModel> intentModels;
    private final RiskScoreEngine riskScoreEngine;

    public RuntimeCapabilitiesController(
        CompanyDataProvider companyDataProvider,
        PublicSearchProviderRegistry searchProviders,
        List<AgentIntentModel> intentModels,
        RiskScoreEngine riskScoreEngine
    ) {
        this.companyDataProvider = companyDataProvider;
        this.searchProviders = searchProviders;
        this.intentModels = List.copyOf(intentModels);
        this.riskScoreEngine = riskScoreEngine;
    }

    @GetMapping("/capabilities")
    public RuntimeCapabilitiesResponse capabilities() {
        String provider = companyDataProvider.providerName();
        String providerMode = "ELASTICSEARCH".equalsIgnoreCase(provider)
            ? "ES_READ_ONLY"
            : "OFFLINE";
        List<SearchProviderStatus> searches = searchProviders.providers().stream()
            .map(searchProvider -> searchStatus(searchProvider.capabilities()))
            .toList();

        long availableIntentModels = intentModels.stream()
            .filter(AgentIntentModel::available)
            .count();
        return new RuntimeCapabilitiesResponse(
            "UP",
            new RuntimeComponent(
                "ENTERPRISE_DATA",
                provider,
                "READY",
                true,
                Map.of("mode", providerMode, "failure_policy", "STOP_ON_FAILURE")
            ),
            searches,
            new RuntimeComponent(
                "AGENT_INTENT_MODEL",
                availableIntentModels == 0 ? "DETERMINISTIC_RULES" : "MODEL_PORT",
                availableIntentModels == 0 ? "RULE_FALLBACK" : "READY",
                availableIntentModels > 0,
                Map.of("configured_models", Long.toString(availableIntentModels))
            ),
            new RuntimeComponent(
                "RISK_SCORING",
                riskScoreEngine.engineVersion(),
                "READY",
                true,
                Map.of(
                    "rule_version", riskScoreEngine.ruleVersion(),
                    "engine_version", riskScoreEngine.engineVersion(),
                    "migration_mode", "FEATURE_COMPLETE_OR_MATERIALIZED_FALLBACK"
                )
            ),
            new RuntimeComponent(
                "REPORT_GENERATION",
                "DOCX_V1",
                "READY",
                true,
                Map.of("format", "DOCX", "template_version", "V1")
            )
        );
    }

    private static SearchProviderStatus searchStatus(
        ProviderCapabilities capabilities
    ) {
        String state = switch (capabilities.mode()) {
            case UNCONFIGURED -> "NOT_CONFIGURED";
            case FIXTURE -> "TEST_ONLY";
            case SEARCH_ENGINE, LLM_SEARCH -> "READY";
        };
        Map<String, String> details = new LinkedHashMap<>();
        details.put(
            "returns_accessible_citations",
            Boolean.toString(capabilities.returnsAccessibleCitations())
        );
        details.put("required", Boolean.toString(capabilities.required()));
        return new SearchProviderStatus(
            capabilities.provider(),
            capabilities.mode().name(),
            state,
            capabilities.mode() == ProviderCapabilities.ProviderMode.SEARCH_ENGINE
                || capabilities.mode() == ProviderCapabilities.ProviderMode.LLM_SEARCH,
            details
        );
    }

    public record RuntimeCapabilitiesResponse(
        String serviceStatus,
        RuntimeComponent dataProvider,
        List<SearchProviderStatus> searchProviders,
        RuntimeComponent agentModel,
        RuntimeComponent riskScoring,
        RuntimeComponent reportGeneration
    ) {
    }

    public record RuntimeComponent(
        String code,
        String name,
        String state,
        boolean configured,
        Map<String, String> details
    ) {
    }

    public record SearchProviderStatus(
        String name,
        String mode,
        String state,
        boolean configured,
        Map<String, String> details
    ) {
    }
}
