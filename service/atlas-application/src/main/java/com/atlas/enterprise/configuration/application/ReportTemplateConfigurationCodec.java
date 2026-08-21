package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.report.port.ManagedReportTemplateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReportTemplateConfigurationCodec {
    public static final String SCHEMA = "atlas-report-template.v1";
    public static final List<String> REQUIRED_FIELDS = List.of(
        "company_name", "unified_credit_code", "legal_representative",
        "registration_status", "risk_score", "risk_level", "public_evidence"
    );
    public static final List<String> RUNTIME_MAPPING_FIELDS = List.of(
        "unified_credit_code", "legal_representative", "registration_status",
        "risk_score", "risk_level", "public_evidence"
    );

    private final ObjectMapper objectMapper;

    public ReportTemplateConfigurationCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isTemplateDocument(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root != null && root.isObject()
                && SCHEMA.equals(root.path("schema_version").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    public TemplateDefinition parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            require(root != null && root.isObject(), "Report template configuration must be an object");
            require(SCHEMA.equals(text(root, "schema_version")),
                "Unsupported report template schema_version");
            require("DOCX".equals(text(root, "format")), "V1 report format must be DOCX");
            String artifactId = text(root, "artifact_id");
            String contentHash = text(root, "content_hash");
            String templateVersion = text(root, "template_version");
            require(artifactId != null && artifactId.matches("[a-f0-9]{64}"),
                "artifact_id must be a SHA-256 value");
            require(contentHash != null && contentHash.matches("[a-f0-9]{64}"),
                "content_hash must be a SHA-256 value");
            require(artifactId.equals(contentHash), "artifact_id and content_hash must match");
            require(templateVersion != null && templateVersion.matches("[A-Za-z0-9._-]{3,64}"),
                "template_version is invalid");
            JsonNode mappingNode = root.path("field_mapping");
            require(mappingNode.isObject(), "field_mapping must be an object");
            Map<String, String> mapping = new LinkedHashMap<>();
            mappingNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                    throw new IllegalArgumentException("field_mapping values must be non-empty strings");
                }
                mapping.put(entry.getKey(), entry.getValue().asText().trim());
            });
            for (String required : REQUIRED_FIELDS) {
                require(mapping.containsKey(required), "field_mapping is missing " + required);
            }
            require("企业名称".equals(mapping.get("company_name")),
                "company_name is a fixed V1 renderer field and cannot be configured");
            List<SkillConfigurationCodec.Dependency> dependencies = dependencies(
                root.path("dependencies")
            );
            return new TemplateDefinition(
                root.path("enabled").asBoolean(true), artifactId,
                text(root, "original_filename"), templateVersion,
                contentHash, Map.copyOf(mapping), dependencies
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid report template configuration JSON", exception);
        }
    }

    public String document(
        ManagedReportTemplateStore.StoredTemplate artifact,
        String templateVersion,
        Map<String, String> mapping,
        List<SkillConfigurationCodec.Dependency> dependencies
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", SCHEMA);
        root.put("enabled", true);
        root.put("format", "DOCX");
        root.put("artifact_id", artifact.artifactId());
        root.put("original_filename", artifact.originalFilename());
        root.put("template_version", templateVersion);
        root.put("content_hash", artifact.contentHash());
        ObjectNode mappingNode = root.putObject("field_mapping");
        mapping.forEach(mappingNode::put);
        ArrayNode dependencyNode = root.putArray("dependencies");
        for (var dependency : dependencies) {
            ObjectNode value = dependencyNode.addObject();
            value.put("config_key", dependency.configKey());
            value.put("category", dependency.category().name());
            value.put("required", dependency.required());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode report template configuration", exception);
        }
    }

    public Map<String, String> defaultMapping() {
        return Map.of(
            "company_name", "企业名称",
            "unified_credit_code", "统一社会信用代码",
            "legal_representative", "法定代表人",
            "registration_status", "经营状态",
            "risk_score", "风险分：",
            "risk_level", "风险标签：",
            "public_evidence", "网络舆情"
        );
    }

    private static List<SkillConfigurationCodec.Dependency> dependencies(JsonNode node) {
        require(node.isArray(), "dependencies must be an array");
        List<SkillConfigurationCodec.Dependency> values = new ArrayList<>();
        for (JsonNode item : node) {
            String key = text(item, "config_key");
            String categoryValue = text(item, "category");
            require(key != null, "dependency config_key is required");
            try {
                values.add(new SkillConfigurationCodec.Dependency(
                    key, ConfigurationCategory.valueOf(categoryValue),
                    item.path("required").asBoolean(true)
                ));
            } catch (Exception exception) {
                throw new IllegalArgumentException("dependency category is invalid");
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record TemplateDefinition(
        boolean enabled,
        String artifactId,
        String originalFilename,
        String templateVersion,
        String contentHash,
        Map<String, String> fieldMapping,
        List<SkillConfigurationCodec.Dependency> dependencies
    ) {}
}
