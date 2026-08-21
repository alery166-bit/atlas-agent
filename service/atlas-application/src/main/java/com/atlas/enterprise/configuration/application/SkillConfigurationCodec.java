package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SkillConfigurationCodec {
    public static final String SCHEMA = "atlas-skill.v1";
    private static final Map<String, SkillSpec> SPECS = Map.of(
        "company.resolve", new SkillSpec(
            "builtin.company.resolve.v1",
            List.of("company.query"),
            List.of("company.identity", "company.candidates"),
            "{\"minimum_confidence\":0.8}"
        ),
        "company.snapshot", new SkillSpec(
            "builtin.company.snapshot.v1",
            List.of("company.identity"),
            List.of("company.snapshot", "source.statuses"),
            "{\"freeze_source_payload\":true}"
        ),
        "intelligence.search", new SkillSpec(
            "builtin.intelligence.search.v1",
            List.of("company.snapshot", "company.aliases"),
            List.of("search.executions", "public.evidence"),
            "{\"require_identity_anchor\":true,\"deduplicate\":true}"
        ),
        "risk.score", new SkillSpec(
            "builtin.risk.score.v1",
            List.of("company.snapshot", "public.evidence"),
            List.of("risk.score_snapshot", "risk.rule_hits"),
            "{\"preserve_legacy_score\":true,\"allow_manual_score\":true}"
        ),
        "report.generate", new SkillSpec(
            "builtin.report.generate.v1",
            List.of("company.snapshot", "risk.score_snapshot", "operator.confirmation"),
            List.of("report.version", "report.document"),
            "{\"format\":\"DOCX\",\"require_operator_confirmation\":true}"
        )
    );

    private final ObjectMapper objectMapper;

    public SkillConfigurationCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isSkillDocument(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root != null && root.isObject()
                && SCHEMA.equals(root.path("schema_version").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    public SkillDefinition parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            require(root != null && root.isObject(), "Skill configuration must be an object");
            require(SCHEMA.equals(text(root, "schema_version")), "Unsupported skill schema_version");
            String skillKey = text(root, "skill_key");
            String executorKey = text(root, "executor_key");
            SkillSpec spec = SPECS.get(skillKey);
            require(spec != null, "Unsupported built-in skill_key");
            require(spec.executorKey().equals(executorKey),
                "Core executor cannot be replaced from configuration");
            String failurePolicy = text(root, "failure_policy");
            require("STOP".equals(failurePolicy),
                "V1 built-in skills always stop on failure; failure_policy is fixed to STOP");
            List<String> inputs = contract(root.path("input_contract"), "input_contract");
            List<String> outputs = contract(root.path("output_contract"), "output_contract");
            require(inputs.equals(spec.inputContract()),
                "input_contract is fixed by the built-in executor");
            require(outputs.equals(spec.outputContract()),
                "output_contract is fixed by the built-in executor");
            JsonNode parameters = root.path("parameters");
            require(parameters.isObject(), "parameters must be an object");
            require(parameters.equals(objectMapper.readTree(spec.parametersJson())),
                "parameters are fixed by the built-in executor in V1");
            List<Dependency> dependencies = dependencies(root.path("dependencies"));
            return new SkillDefinition(
                skillKey, executorKey, root.path("enabled").asBoolean(true),
                failurePolicy, inputs, outputs, parameters.deepCopy(), dependencies
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid skill configuration JSON", exception);
        }
    }

    public Map<String, String> executors() {
        Map<String, String> values = new LinkedHashMap<>();
        SPECS.forEach((key, spec) -> values.put(key, spec.executorKey()));
        return values;
    }

    private static List<String> contract(JsonNode node, String field) {
        require(node.isArray() && !node.isEmpty(), field + " must not be empty");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            require(!value.isEmpty() && value.matches("[a-z][a-z0-9_.]{1,63}"),
                field + " contains an invalid field name");
            values.add(value);
        }
        require(values.stream().distinct().count() == values.size(), field + " contains duplicates");
        return List.copyOf(values);
    }

    private static List<Dependency> dependencies(JsonNode node) {
        require(node.isArray(), "dependencies must be an array");
        List<Dependency> values = new ArrayList<>();
        for (JsonNode item : node) {
            String configKey = text(item, "config_key");
            String categoryValue = text(item, "category");
            require(configKey != null && configKey.matches("[a-z0-9][a-z0-9._-]{2,127}"),
                "dependency config_key is invalid");
            ConfigurationCategory category;
            try {
                category = ConfigurationCategory.valueOf(categoryValue);
            } catch (Exception exception) {
                throw new IllegalArgumentException("dependency category is invalid");
            }
            values.add(new Dependency(configKey, category, item.path("required").asBoolean(true)));
        }
        require(values.stream().map(Dependency::configKey).distinct().count() == values.size(),
            "dependencies contain duplicate config_key values");
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record SkillDefinition(
        String skillKey,
        String executorKey,
        boolean enabled,
        String failurePolicy,
        List<String> inputContract,
        List<String> outputContract,
        JsonNode parameters,
        List<Dependency> dependencies
    ) {}

    public record Dependency(
        String configKey,
        ConfigurationCategory category,
        boolean required
    ) {}

    private record SkillSpec(
        String executorKey,
        List<String> inputContract,
        List<String> outputContract,
        String parametersJson
    ) {}
}
