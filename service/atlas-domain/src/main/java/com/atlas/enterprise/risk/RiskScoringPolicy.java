package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable, task-frozen subset of the published risk policy used at runtime. */
public record RiskScoringPolicy(
    String version,
    Map<RiskType, BigDecimal> eventFloors,
    Map<String, BigDecimal> ruleWeights,
    Set<String> disabledLegacyLabels,
    int riskEventWindowDays,
    int companyChangeWindowDays
) {
    public RiskScoringPolicy {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Risk policy version is required");
        }
        EnumMap<RiskType, BigDecimal> normalizedFloors = new EnumMap<>(RiskType.class);
        if (eventFloors != null) {
            normalizedFloors.putAll(eventFloors);
        }
        eventFloors = Map.copyOf(normalizedFloors);
        ruleWeights = ruleWeights == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(ruleWeights));
        disabledLegacyLabels = disabledLegacyLabels == null
            ? Set.of()
            : Set.copyOf(disabledLegacyLabels);
        if (riskEventWindowDays < 1 || riskEventWindowDays > 3650
            || companyChangeWindowDays < 1 || companyChangeWindowDays > 3650) {
            throw new IllegalArgumentException("Risk policy time windows must be in [1,3650] days");
        }
        eventFloors.forEach((type, score) -> validateScore(score, "event floor " + type));
        ruleWeights.forEach((code, score) -> validateScore(score, "rule weight " + code));
    }

    public static RiskScoringPolicy defaultPolicy() {
        EnumMap<RiskType, BigDecimal> floors = new EnumMap<>(RiskType.class);
        floors.put(RiskType.OUT_OF_CONTACT, new BigDecimal("6"));
        floors.put(RiskType.WAGE_ARREARS, new BigDecimal("6"));
        floors.put(RiskType.STORE_CLOSURE, new BigDecimal("8"));
        return new RiskScoringPolicy(
            LegacyRiskScoreEngineV1.RULE_VERSION,
            floors,
            Map.of(),
            Set.of(),
            365,
            180
        );
    }

    public BigDecimal floorFor(RiskType type) {
        return eventFloors.getOrDefault(type, BigDecimal.ZERO);
    }

    public BigDecimal weight(String ruleCode, String fallback) {
        return ruleWeights.getOrDefault(ruleCode, new BigDecimal(fallback));
    }

    public boolean legacyLabelEnabled(String legacyLabelNo) {
        return !disabledLegacyLabels.contains(legacyLabelNo);
    }

    private static void validateScore(BigDecimal score, String field) {
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0
            || score.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException(field + " must be in [0,10]");
        }
    }
}
