package com.atlas.enterprise.configuration.api;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.application.ConfigurationApplicationService;
import com.atlas.enterprise.configuration.application.ConfigurationOverview;
import com.atlas.enterprise.configuration.application.SkillConfigurationCodec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/skills")
public class SkillAdminController {
    private final ConfigurationApplicationService configurations;
    private final SkillConfigurationCodec codec;

    public SkillAdminController(
        ConfigurationApplicationService configurations,
        SkillConfigurationCodec codec
    ) {
        this.configurations = configurations;
        this.codec = codec;
    }

    @GetMapping
    public List<ConfigurationOverview> list(
        @RequestParam(defaultValue = "DEV") String environment
    ) {
        return configurations.list(environment).stream()
            .filter(item -> item.definition().category() == ConfigurationCategory.SKILL)
            .filter(item -> item.versions().stream()
                .anyMatch(version -> codec.isSkillDocument(version.valueJson())))
            .toList();
    }

    @GetMapping("/catalog")
    public Map<String, String> catalog() {
        return codec.executors();
    }

    @PostMapping("/initialize")
    public List<ConfigurationOverview> initialize(@Valid @RequestBody InitializeRequest request) {
        Set<String> existing = configurations.list(request.environment()).stream()
            .map(item -> item.definition().configKey()).collect(java.util.stream.Collectors.toSet());
        for (Template template : templates()) {
            if (existing.contains(template.configKey())) continue;
            configurations.create(
                template.configKey(), ConfigurationCategory.SKILL,
                template.displayName(), template.description(), false,
                template.valueJson(), null, request.operatorId()
            );
        }
        return list(request.environment());
    }

    private static List<Template> templates() {
        List<Template> values = new ArrayList<>();
        values.add(template(
            "skill.company.resolve", "企业主体识别", "按企业名或信用代码解析唯一主体",
            "company.resolve", "builtin.company.resolve.v1",
            List.of("company.query"), List.of("company.identity", "company.candidates"),
            "{\"minimum_confidence\":0.8}",
            "[{\"config_key\":\"data-source.es.primary\",\"category\":\"DATA_SOURCE\",\"required\":false}]"
        ));
        values.add(template(
            "skill.company.snapshot", "企业数据快照", "冻结工商、经营和风险输入",
            "company.snapshot", "builtin.company.snapshot.v1",
            List.of("company.identity"), List.of("company.snapshot", "source.statuses"),
            "{\"freeze_source_payload\":true}",
            "[{\"config_key\":\"data-source.es.primary\",\"category\":\"DATA_SOURCE\",\"required\":false}]"
        ));
        values.add(template(
            "skill.intelligence.search", "公开信息检索", "按身份词检索并形成可追溯证据",
            "intelligence.search", "builtin.intelligence.search.v1",
            List.of("company.snapshot", "company.aliases"),
            List.of("search.executions", "public.evidence"),
            "{\"require_identity_anchor\":true,\"deduplicate\":true}",
            "[{\"config_key\":\"search.tavily.primary\",\"category\":\"SEARCH\",\"required\":false}]"
        ));
        values.add(template(
            "skill.risk.score", "风险评分", "执行已发布确定性规则并保留旧分",
            "risk.score", "builtin.risk.score.v1",
            List.of("company.snapshot", "public.evidence"),
            List.of("risk.score_snapshot", "risk.rule_hits"),
            "{\"preserve_legacy_score\":true,\"allow_manual_score\":true}",
            "[{\"config_key\":\"risk.rules.standard-v1\",\"category\":\"RULES\",\"required\":false}]"
        ));
        values.add(template(
            "skill.report.generate", "DOCX 报告生成", "基于冻结输入生成版本化正式报告",
            "report.generate", "builtin.report.generate.v1",
            List.of("company.snapshot", "risk.score_snapshot", "operator.confirmation"),
            List.of("report.version", "report.document"),
            "{\"format\":\"DOCX\",\"require_operator_confirmation\":true}",
            "[{\"config_key\":\"report-template.risk-v1\",\"category\":\"REPORT_TEMPLATE\",\"required\":false}]"
        ));
        return List.copyOf(values);
    }

    private static Template template(
        String configKey,
        String displayName,
        String description,
        String skillKey,
        String executorKey,
        List<String> inputs,
        List<String> outputs,
        String parameters,
        String dependencies
    ) {
        String inputJson = inputs.stream().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        String outputJson = outputs.stream().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        String json = "{\"schema_version\":\"atlas-skill.v1\",\"skill_key\":\""
            + skillKey + "\",\"executor_key\":\"" + executorKey
            + "\",\"enabled\":true,\"failure_policy\":\"STOP\",\"input_contract\":["
            + inputJson + "],\"output_contract\":[" + outputJson
            + "],\"parameters\":" + parameters + ",\"dependencies\":" + dependencies + "}";
        return new Template(configKey, displayName, description, json);
    }

    public record InitializeRequest(
        @NotBlank String environment,
        @NotBlank String operatorId
    ) {}

    private record Template(
        String configKey,
        String displayName,
        String description,
        String valueJson
    ) {}
}
