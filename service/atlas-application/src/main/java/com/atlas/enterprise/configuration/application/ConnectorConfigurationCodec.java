package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ConnectorConfigurationCodec {
    public static final String SCHEMA = "atlas-connector.v1";
    private final ObjectMapper objectMapper;

    public ConnectorConfigurationCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isConnectorDocument(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root != null && root.isObject()
                && SCHEMA.equals(root.path("schema_version").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    public ConnectorDefinition parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            require(root != null && root.isObject(), "Connector configuration must be an object");
            require(SCHEMA.equals(text(root, "schema_version")), "Unsupported connector schema_version");
            ConfigurationCategory category = ConfigurationCategory.valueOf(text(root, "category"));
            require(category == ConfigurationCategory.DATA_SOURCE
                    || category == ConfigurationCategory.SEARCH
                    || category == ConfigurationCategory.MODEL,
                "Connector category must be DATA_SOURCE, SEARCH or MODEL");
            String kind = text(root, "kind");
            require(kind != null && kind.matches("[A-Z0-9_]{2,40}"), "Connector kind is invalid");
            boolean enabled = root.path("enabled").asBoolean(false);
            boolean required = root.path("required").asBoolean(false);
            String failurePolicy = text(root, "failure_policy");
            require("STOP".equals(failurePolicy) || "OPTIONAL".equals(failurePolicy),
                "failure_policy must be STOP or OPTIONAL");
            require((required && "STOP".equals(failurePolicy))
                    || (!required && "OPTIONAL".equals(failurePolicy)),
                "failure_policy is derived from required: required=STOP, optional=OPTIONAL");

            JsonNode endpoint = object(root, "endpoint");
            URI baseUri = URI.create(text(endpoint, "base_url"));
            require(("http".equalsIgnoreCase(baseUri.getScheme())
                    || "https".equalsIgnoreCase(baseUri.getScheme()))
                    && baseUri.getHost() != null,
                "endpoint.base_url must be an HTTP(S) URL");
            String path = text(endpoint, "path");
            require(path != null && path.startsWith("/"), "endpoint.path must start with /");
            int connectTimeout = integer(endpoint, "connect_timeout_ms", 100, 120000);
            int requestTimeout = integer(endpoint, "request_timeout_ms", 100, 300000);

            JsonNode retry = object(root, "retry");
            int attempts = integer(retry, "max_attempts", 1, 5);
            int backoff = integer(retry, "backoff_ms", 0, 60000);
            String credentialRef = optionalText(root, "credential_ref");
            require(credentialRef == null || credentialRef.matches("(env|vault|file):[A-Za-z0-9_./-]{2,200}"),
                "credential_ref must be an env:, vault: or file: server reference");
            require(!enabled || !requiresCredential(kind) || credentialRef != null,
                "Enabled " + kind + " connector requires credential_ref");

            Map<String, String> mapping = stringMap(root.path("field_mapping"));
            JsonNode settings = root.path("settings");
            require(settings.isMissingNode() || settings.isObject(), "settings must be an object");
            validateSpecific(category, kind, root, mapping, settings);
            return new ConnectorDefinition(
                category, kind, enabled, required, failurePolicy,
                baseUri, path, Duration.ofMillis(connectTimeout),
                Duration.ofMillis(requestTimeout), attempts, backoff,
                credentialRef, mapping, settings.isObject() ? settings : objectMapper.createObjectNode()
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Connector configuration is invalid", exception);
        }
    }

    public Map<String, Object> preview(ConnectorDefinition definition, Map<String, Object> sample) {
        Map<String, Object> result = new LinkedHashMap<>();
        definition.fieldMapping().forEach((target, source) ->
            result.put(target, pathValue(sample, source))
        );
        return result;
    }

    private static void validateSpecific(
        ConfigurationCategory category, String kind, JsonNode root,
        Map<String, String> mapping, JsonNode settings
    ) {
        if (category == ConfigurationCategory.DATA_SOURCE) {
            require("ELASTICSEARCH".equals(kind), "V1 data source kind must be ELASTICSEARCH");
            JsonNode indices = object(root, "indices");
            for (String name : new String[]{"company", "event", "public_intelligence", "contact"}) {
                require(text(indices, name) != null, "indices." + name + " is required");
            }
            for (String name : new String[]{"canonical_name", "unified_credit_code", "source_entity_id"}) {
                require(mapping.containsKey(name), "field_mapping." + name + " is required");
            }
        } else if (category == ConfigurationCategory.SEARCH) {
            require("TAVILY".equals(kind), "V1 search kind must be TAVILY");
            require(settings.path("max_results").asInt(0) >= 1
                    && settings.path("max_results").asInt() <= 20,
                "settings.max_results must be in [1,20]");
            require(java.util.List.of("basic", "advanced").contains(text(settings, "search_depth")),
                "settings.search_depth is invalid");
            require(java.util.List.of("general", "news").contains(text(settings, "topic")),
                "settings.topic is invalid");
            if ("IDENTITY_SOURCE_AGGREGATION".equals(optionalText(settings, "strategy"))) {
                JsonNode scopes = settings.path("source_scopes");
                require(scopes.isArray() && !scopes.isEmpty(),
                    "settings.source_scopes must not be empty");
                require(scopes.size() <= 10,
                    "settings.source_scopes must contain no more than 10 entries");
                for (JsonNode scope : scopes) {
                    require(scope.isObject(), "settings.source_scopes entries must be objects");
                    require(text(scope, "code").matches("[A-Z0-9_]{2,40}"),
                        "settings.source_scopes.code is invalid");
                    require(java.util.List.of("general", "news").contains(text(scope, "topic")),
                        "settings.source_scopes.topic is invalid");
                    require(scope.path("include_raw_content").isBoolean(),
                        "settings.source_scopes.include_raw_content must be boolean");
                    JsonNode domains = scope.path("include_domains");
                    require(domains.isArray() && domains.size() <= 20,
                        "settings.source_scopes.include_domains must be an array with at most 20 entries");
                    for (JsonNode domain : domains) {
                        require(domain.isTextual()
                                && domain.asText().matches("[A-Za-z0-9.-]{3,253}"),
                            "settings.source_scopes.include_domains contains an invalid domain");
                    }
                }
            } else {
                require(settings.path("query_templates").isArray()
                        && !settings.path("query_templates").isEmpty(),
                    "settings.query_templates must not be empty");
                require(settings.path("query_templates").size() <= 20,
                    "settings.query_templates must contain no more than 20 entries");
                for (JsonNode template : settings.path("query_templates")) {
                    require(template.isTextual() && !template.asText().isBlank(),
                        "settings.query_templates entries must be non-empty strings");
                    require(template.asText().trim().length() <= 300,
                        "settings.query_templates entries must not exceed 300 characters");
                }
            }
        } else {
            require("OPENAI_COMPATIBLE_LLM".equals(kind),
                "V1 model kind must be OPENAI_COMPATIBLE_LLM");
            require(text(settings, "model") != null, "settings.model is required");
            require(text(settings, "prompt_template") != null, "settings.prompt_template is required");
            require(settings.path("citation_required").asBoolean(false),
                "Model connector must require citations");
            require(settings.path("temperature").asDouble(-1D) >= 0D
                    && settings.path("temperature").asDouble() <= 2D,
                "settings.temperature must be in [0,2]");
            require(settings.path("max_tokens").asInt(0) >= 64
                    && settings.path("max_tokens").asInt() <= 32768,
                "settings.max_tokens must be in [64,32768]");
            require(settings.path("intent_enabled").isBoolean(),
                "settings.intent_enabled must be boolean");
            require(settings.path("evidence_review_enabled").isBoolean(),
                "settings.evidence_review_enabled must be boolean");
            require(settings.path("automatic_evidence_decision_enabled").isMissingNode()
                    || settings.path("automatic_evidence_decision_enabled").isBoolean(),
                "settings.automatic_evidence_decision_enabled must be boolean");
            if (!settings.path("automatic_decision_threshold").isMissingNode()) {
                require(settings.path("automatic_decision_threshold").isNumber()
                        && settings.path("automatic_decision_threshold").asDouble() >= 0.8D
                        && settings.path("automatic_decision_threshold").asDouble() <= 1D,
                    "settings.automatic_decision_threshold must be in [0.8,1]");
            }
        }
    }

    private static boolean requiresCredential(String kind) {
        return "TAVILY".equals(kind) || "OPENAI_COMPATIBLE_LLM".equals(kind);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node.isMissingNode()) return Map.of();
        require(node.isObject(), "field_mapping must be an object");
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            require(field.getValue().isTextual() && !field.getValue().asText().isBlank(),
                "field_mapping values must be field paths");
            values.put(field.getKey(), field.getValue().asText().trim());
        }
        return Map.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private static Object pathValue(Map<String, Object> sample, String path) {
        Object current = sample;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isObject(), field + " must be an object");
        return value;
    }

    private static int integer(JsonNode node, String field, int min, int max) {
        int value = node.path(field).asInt(Integer.MIN_VALUE);
        require(value >= min && value <= max, field + " must be in [" + min + "," + max + "]");
        return value;
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        require(value != null, field + " is required");
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record ConnectorDefinition(
        ConfigurationCategory category,
        String kind,
        boolean enabled,
        boolean required,
        String failurePolicy,
        URI baseUri,
        String path,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        int backoffMs,
        String credentialRef,
        Map<String, String> fieldMapping,
        JsonNode settings
    ) {}
}
