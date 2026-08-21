package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.risk.RiskScoringPolicy;
import com.atlas.enterprise.risk.RiskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RiskRulePolicyCodec {
    public static final String SCHEMA_VERSION = "atlas-risk-policy.v1";
    public static final String CONFIG_KEY = "risk.rules.v1";
    private static final Set<String> SUPPORTED_RULE_WEIGHTS = Set.of(
        "LEGACY_NEGATIVE_SENTIMENT_KEYWORD",
        "LEGACY_NEGATIVE_SENTIMENT",
        "LEGACY_COMPLAINT",
        "LEGACY_JUDICIAL_KEYWORD",
        "LEGACY_JUDICIAL_DEFENDANT",
        "LEGACY_BUSINESS_ABNORMAL",
        "LEGACY_SERIOUS_ILLEGAL",
        "LEGACY_ADMINISTRATIVE_PENALTY",
        "LEGACY_EQUITY_PLEDGE",
        "LEGACY_STOCK_PLEDGE",
        "LEGACY_EQUITY_FREEZE"
    );

    private final ObjectMapper objectMapper;

    public RiskRulePolicyCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedPolicy parse(String valueJson, String version) {
        try {
            JsonNode root = objectMapper.readTree(valueJson);
            require(root != null && root.isObject(), "Rule policy must be a JSON object");
            require(SCHEMA_VERSION.equals(text(root, "schema_version")),
                "schema_version must be " + SCHEMA_VERSION);

            JsonNode thresholds = requireObject(root, "thresholds");
            BigDecimal high = score(thresholds, "high_min");
            BigDecimal mediumHigh = score(thresholds, "medium_high_min");
            BigDecimal medium = score(thresholds, "medium_min");
            BigDecimal mediumLow = score(thresholds, "medium_low_min");
            require(high.compareTo(mediumHigh) > 0
                    && mediumHigh.compareTo(medium) > 0
                    && medium.compareTo(mediumLow) > 0,
                "Risk thresholds must be strictly descending");
            require(high.compareTo(new BigDecimal("8")) == 0
                    && mediumHigh.compareTo(new BigDecimal("6")) == 0
                    && medium.compareTo(new BigDecimal("4")) == 0
                    && mediumLow.compareTo(new BigDecimal("2")) == 0,
                "V1 risk thresholds are fixed at 8/6/4/2 and are not runtime-configurable");

            EnumMap<RiskType, BigDecimal> floors = new EnumMap<>(RiskType.class);
            JsonNode floorNodes = root.path("event_floors");
            require(floorNodes.isArray(), "event_floors must be an array");
            for (JsonNode item : floorNodes) {
                if (!item.path("enabled").asBoolean(true)) {
                    continue;
                }
                RiskType type = riskType(item.path("risk_type").asText());
                require(type != RiskType.OTHER, "Unknown event floor risk_type");
                require(!floors.containsKey(type), "Duplicate event floor for " + type);
                floors.put(type, score(item, "minimum_score"));
                require(item.path("evidence_required").asBoolean(false),
                    "Enabled event floor must require evidence: " + type);
            }
            minimumFloor(floors, RiskType.OUT_OF_CONTACT, "6");
            minimumFloor(floors, RiskType.WAGE_ARREARS, "6");
            minimumFloor(floors, RiskType.STORE_CLOSURE, "8");

            Map<String, BigDecimal> weights = new LinkedHashMap<>();
            JsonNode weightNodes = root.path("rule_weights");
            require(weightNodes.isMissingNode() || weightNodes.isObject(),
                "rule_weights must be an object");
            if (weightNodes.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = weightNodes.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    require(field.getKey().matches("[A-Z0-9_]{3,80}"),
                        "Invalid rule weight code: " + field.getKey());
                    require(SUPPORTED_RULE_WEIGHTS.contains(field.getKey()),
                        "Rule weight is not consumed by the V1 scoring engine: " + field.getKey());
                    weights.put(field.getKey(), decimal(field.getValue(), field.getKey()));
                }
            }
            require(weights.keySet().equals(SUPPORTED_RULE_WEIGHTS),
                "rule_weights must contain exactly the V1 scoring engine weight keys");

            Set<String> disabledLabels = new LinkedHashSet<>();
            JsonNode labelNodes = root.path("risk_labels");
            require(labelNodes.isMissingNode() || labelNodes.isArray(),
                "risk_labels must be an array");
            if (labelNodes.isArray()) {
                require(labelNodes.isEmpty(),
                    "V1 risk label logic is not implemented; risk_labels must remain empty");
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode item : labelNodes) {
                    String legacyNo = text(item, "legacy_label_no");
                    require(legacyNo != null && legacyNo.matches("[0-9]{6,18}"),
                        "risk_labels.legacy_label_no is invalid");
                    require(seen.add(legacyNo), "Duplicate legacy label: " + legacyNo);
                    require(text(item, "category") != null, "Risk label category is required");
                    require(text(item, "evidence_requirement") != null,
                        "Risk label evidence_requirement is required");
                    int priority = item.path("priority").asInt(-1);
                    require(priority >= 0 && priority <= 1000,
                        "Risk label priority must be in [0,1000]");
                    if (!item.path("enabled").asBoolean(true)) {
                        disabledLabels.add(legacyNo);
                    }
                }
            }

            JsonNode windows = requireObject(root, "time_windows");
            int riskDays = days(windows, "risk_event_days");
            int changeDays = days(windows, "company_change_days");

            JsonNode gate = requireObject(root, "replay_gate");
            int minimumSamples = gate.path("minimum_samples").asInt(-1);
            require(minimumSamples >= 20 && minimumSamples <= 50,
                "replay_gate.minimum_samples must be in [20,50]");
            BigDecimal tolerance = decimal(gate.path("max_score_delta"), "max_score_delta");
            require(tolerance.compareTo(BigDecimal.ZERO) >= 0
                    && tolerance.compareTo(new BigDecimal("2")) <= 0,
                "replay_gate.max_score_delta must be in [0,2]");
            boolean allowLevelChanges = gate.path("allow_level_changes").asBoolean(false);

            return new ParsedPolicy(
                new RiskScoringPolicy(
                    version, floors, weights, disabledLabels, riskDays, changeDays
                ),
                new Thresholds(high, mediumHigh, medium, mediumLow),
                new ReplayGate(minimumSamples, tolerance, allowLevelChanges)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Rule policy is not valid JSON", exception);
        }
    }

    public String defaultJson() {
        return """
            {
              "schema_version":"atlas-risk-policy.v1",
              "name":"企业标准风险评分规则",
              "thresholds":{"high_min":8,"medium_high_min":6,"medium_min":4,"medium_low_min":2},
              "event_floors":[
                {"risk_type":"OUT_OF_CONTACT","minimum_score":6,"enabled":true,"evidence_required":true},
                {"risk_type":"WAGE_ARREARS","minimum_score":6,"enabled":true,"evidence_required":true},
                {"risk_type":"STORE_CLOSURE","minimum_score":8,"enabled":true,"evidence_required":true}
              ],
              "rule_weights":{
                "LEGACY_NEGATIVE_SENTIMENT_KEYWORD":3.0,
                "LEGACY_NEGATIVE_SENTIMENT":2.4,
                "LEGACY_COMPLAINT":2.0,
                "LEGACY_JUDICIAL_KEYWORD":2.5,
                "LEGACY_JUDICIAL_DEFENDANT":2.0,
                "LEGACY_BUSINESS_ABNORMAL":0.5,
                "LEGACY_SERIOUS_ILLEGAL":0.5,
                "LEGACY_ADMINISTRATIVE_PENALTY":0.5,
                "LEGACY_EQUITY_PLEDGE":0.5,
                "LEGACY_STOCK_PLEDGE":0.5,
                "LEGACY_EQUITY_FREEZE":0.5
              },
              "risk_labels":[],
              "time_windows":{"risk_event_days":365,"company_change_days":180},
              "replay_gate":{"minimum_samples":20,"max_score_delta":0.5,"allow_level_changes":false}
            }
            """.trim();
    }

    private static JsonNode requireObject(JsonNode root, String field) {
        JsonNode value = root.path(field);
        require(value.isObject(), field + " must be an object");
        return value;
    }

    private static int days(JsonNode node, String field) {
        int value = node.path(field).asInt(-1);
        require(value >= 1 && value <= 3650, field + " must be in [1,3650]");
        return value;
    }

    private static BigDecimal score(JsonNode node, String field) {
        BigDecimal value = decimal(node.path(field), field);
        require(value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.TEN) <= 0,
            field + " must be in [0,10]");
        return value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        require(node != null && node.isNumber(), field + " must be numeric");
        return node.decimalValue();
    }

    private static void minimumFloor(
        Map<RiskType, BigDecimal> floors,
        RiskType type,
        String minimum
    ) {
        require(floors.containsKey(type), "Mandatory event floor is missing: " + type);
        require(floors.get(type).compareTo(new BigDecimal(minimum)) >= 0,
            type + " minimum score cannot be lower than " + minimum);
    }

    private static RiskType riskType(String value) {
        try {
            return RiskType.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return RiskType.OTHER;
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public record ParsedPolicy(
        RiskScoringPolicy runtime,
        Thresholds thresholds,
        ReplayGate replayGate
    ) {}

    public record Thresholds(
        BigDecimal highMin,
        BigDecimal mediumHighMin,
        BigDecimal mediumMin,
        BigDecimal mediumLowMin
    ) {
        public String level(BigDecimal score) {
            if (score.compareTo(highMin) >= 0) return "HIGH";
            if (score.compareTo(mediumHighMin) >= 0) return "MEDIUM_HIGH";
            if (score.compareTo(mediumMin) >= 0) return "MEDIUM";
            if (score.compareTo(mediumLowMin) >= 0) return "MEDIUM_LOW";
            return "LOW";
        }
    }

    public record ReplayGate(
        int minimumSamples,
        BigDecimal maxScoreDelta,
        boolean allowLevelChanges
    ) {}
}
