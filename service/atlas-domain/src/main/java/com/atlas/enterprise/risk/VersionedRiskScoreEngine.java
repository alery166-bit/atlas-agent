package com.atlas.enterprise.risk;

public final class VersionedRiskScoreEngine implements RiskScoreEngine {
    public static final String RULE_VERSION = LegacyRiskScoreEngineV1.RULE_VERSION;
    public static final String ENGINE_VERSION = LegacyRiskScoreEngineV1.ENGINE_VERSION;

    private final LegacyRiskScoreEngineV1 legacyEngine;

    public VersionedRiskScoreEngine() {
        this(new LegacyRiskScoreAdapter());
    }

    VersionedRiskScoreEngine(LegacyRiskScoreAdapter legacyAdapter) {
        this.legacyEngine = new LegacyRiskScoreEngineV1(legacyAdapter);
    }

    @Override
    public RiskScoreResult calculate(RiskScoreRequest request) {
        return legacyEngine.calculate(request);
    }

    @Override
    public String ruleVersion() {
        return legacyEngine.ruleVersion();
    }

    @Override
    public String engineVersion() {
        return ENGINE_VERSION;
    }
}
