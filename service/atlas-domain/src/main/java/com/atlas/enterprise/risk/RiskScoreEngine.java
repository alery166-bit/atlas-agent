package com.atlas.enterprise.risk;

public interface RiskScoreEngine {
    RiskScoreResult calculate(RiskScoreRequest request);

    String ruleVersion();

    String engineVersion();
}
