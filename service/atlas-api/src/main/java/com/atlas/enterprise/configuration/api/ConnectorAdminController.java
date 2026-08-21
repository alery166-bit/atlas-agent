package com.atlas.enterprise.configuration.api;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConnectorTestRun;
import com.atlas.enterprise.configuration.application.ConfigurationApplicationService;
import com.atlas.enterprise.configuration.application.ConfigurationOverview;
import com.atlas.enterprise.configuration.application.ConnectorConfigurationCodec;
import com.atlas.enterprise.configuration.application.ConnectorManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/connectors")
public class ConnectorAdminController {
    private final ConfigurationApplicationService configurations;
    private final ConnectorManagementService connectors;
    private final ConnectorConfigurationCodec connectorCodec;

    public ConnectorAdminController(
        ConfigurationApplicationService configurations,
        ConnectorManagementService connectors,
        ConnectorConfigurationCodec connectorCodec
    ) {
        this.configurations = configurations;
        this.connectors = connectors;
        this.connectorCodec = connectorCodec;
    }

    @GetMapping
    public List<ConnectorOverview> list(
        @RequestParam(defaultValue = "DEV") String environment
    ) {
        return configurations.list(environment).stream()
            .filter(item -> item.definition().category() == ConfigurationCategory.DATA_SOURCE
                || item.definition().category() == ConfigurationCategory.SEARCH
                || item.definition().category() == ConfigurationCategory.MODEL)
            .filter(item -> item.versions().stream()
                .anyMatch(version -> connectorCodec.isConnectorDocument(version.valueJson())))
            .map(item -> new ConnectorOverview(
                item,
                item.versions().stream().map(version -> new TestImpact(
                    version.versionId(),
                    connectors.history(version.versionId()).stream().findFirst().orElse(null)
                )).toList()
            )).toList();
    }

    @PostMapping("/initialize")
    public ConfigurationOverview initialize(@Valid @RequestBody InitializeRequest request) {
        Template template = template(request.type());
        return configurations.create(
            template.key(), template.category(), template.name(), template.description(),
            false, template.json(), null, request.operatorId()
        );
    }

    @PostMapping("/versions/{versionId}/tests")
    public ConnectorTestRun test(
        @PathVariable UUID versionId,
        @Valid @RequestBody TestRequest request
    ) {
        return connectors.test(versionId, request.sampleRecord(), request.operatorId());
    }

    @PostMapping("/versions/{versionId}/mapping-preview")
    public Map<String, Object> preview(
        @PathVariable UUID versionId,
        @Valid @RequestBody PreviewRequest request
    ) {
        return connectors.preview(versionId, request.sampleRecord());
    }

    @GetMapping("/versions/{versionId}/tests")
    public List<ConnectorTestRun> history(@PathVariable UUID versionId) {
        return connectors.history(versionId);
    }

    private static Template template(String type) {
        return switch (type == null ? "" : type.trim().toUpperCase()) {
            case "ELASTICSEARCH" -> new Template(
                "data-source.es.primary", ConfigurationCategory.DATA_SOURCE,
                "Elasticsearch 企业数据", "企业主档、事件、舆情和联系方式只读数据源",
                """
                {"schema_version":"atlas-connector.v1","category":"DATA_SOURCE","kind":"ELASTICSEARCH","enabled":false,"required":true,"failure_policy":"STOP","endpoint":{"base_url":"http://elasticsearch-dev:9200","path":"/","connect_timeout_ms":3000,"request_timeout_ms":15000},"retry":{"max_attempts":1,"backoff_ms":0},"indices":{"company":"atlas-company-read","event":"atlas-company-event-read","public_intelligence":"atlas-public-intel-read","contact":"atlas-company-contact-read"},"field_mapping":{"canonical_name":"company_name","unified_credit_code":"unified_credit_code","source_entity_id":"md5"},"settings":{"max_records":5000}}
                """.trim()
            );
            case "TAVILY" -> new Template(
                "search.tavily.primary", ConfigurationCategory.SEARCH,
                "Tavily 公开信息聚合", "按企业身份词聚合公开网页与投诉平台，风险在内容研判阶段识别",
                """
                {"schema_version":"atlas-connector.v1","category":"SEARCH","kind":"TAVILY","enabled":false,"required":true,"failure_policy":"STOP","credential_ref":"env:ATLAS_SEARCH_PRIMARY_API_KEY","endpoint":{"base_url":"https://api.tavily.com","path":"/search","connect_timeout_ms":5000,"request_timeout_ms":30000},"retry":{"max_attempts":3,"backoff_ms":250},"settings":{"strategy":"IDENTITY_SOURCE_AGGREGATION","search_depth":"advanced","topic":"general","max_results":10,"rate_limit_per_minute":60,"source_scopes":[{"code":"GENERAL_WEB","label":"综合公开网页","topic":"general","include_domains":[],"include_raw_content":true},{"code":"COMPLAINT_PLATFORMS","label":"投诉平台","topic":"general","include_domains":["tousu.sina.com.cn","finance.cnr.cn","xfb365.com"],"include_raw_content":true}]}}
                """.trim()
            );
            case "MODEL" -> new Template(
                "model.cited-llm.primary", ConfigurationCategory.MODEL,
                "阿里云百炼语义模型", "意图理解与证据辅助研判，不计算确定性评分",
                """
                {"schema_version":"atlas-connector.v1","category":"MODEL","kind":"OPENAI_COMPATIBLE_LLM","enabled":false,"required":false,"failure_policy":"OPTIONAL","credential_ref":"env:ATLAS_LLM_API_KEY","endpoint":{"base_url":"https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1","path":"/chat/completions","connect_timeout_ms":5000,"request_timeout_ms":60000},"retry":{"max_attempts":2,"backoff_ms":500},"settings":{"provider":"ALIYUN_BAILIAN","model":"qwen3.8-max","temperature":0.1,"max_tokens":4096,"intent_enabled":true,"evidence_review_enabled":true,"automatic_evidence_decision_enabled":true,"automatic_decision_threshold":0.9,"prompt_template":"只判断输入证据是否明确关联目标企业，区分企业全称、简称和品牌；重点识别失联、欠薪、闭店风险，不得补充输入以外的事实。","citation_required":true,"citation_threshold":0.8}}
                """.trim()
            );
            default -> throw new IllegalArgumentException("Unknown connector type");
        };
    }

    public record InitializeRequest(@NotBlank String type, @NotBlank String operatorId) {}
    public record TestRequest(@NotBlank String operatorId, Map<String, Object> sampleRecord) {}
    public record PreviewRequest(@NotNull Map<String, Object> sampleRecord) {}
    public record ConnectorOverview(ConfigurationOverview configuration, List<TestImpact> testImpacts) {}
    public record TestImpact(UUID versionId, ConnectorTestRun latestTest) {}
    private record Template(String key, ConfigurationCategory category, String name,
                            String description, String json) {}
}
