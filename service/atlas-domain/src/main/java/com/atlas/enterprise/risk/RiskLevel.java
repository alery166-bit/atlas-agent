package com.atlas.enterprise.risk;

import java.math.BigDecimal;

public enum RiskLevel {
    LOW,
    MEDIUM_LOW,
    MEDIUM,
    MEDIUM_HIGH,
    HIGH;

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal FOUR = new BigDecimal("4");
    private static final BigDecimal SIX = new BigDecimal("6");
    private static final BigDecimal EIGHT = new BigDecimal("8");
    private static final BigDecimal TEN = new BigDecimal("10");

    public static RiskLevel from(BigDecimal score) {
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(TEN) > 0) {
            throw new IllegalArgumentException("score must be in [0,10]");
        }
        if (score.compareTo(EIGHT) >= 0) {
            return HIGH;
        }
        if (score.compareTo(SIX) >= 0) {
            return MEDIUM_HIGH;
        }
        if (score.compareTo(FOUR) >= 0) {
            return MEDIUM;
        }
        if (score.compareTo(TWO) >= 0) {
            return MEDIUM_LOW;
        }
        return LOW;
    }
}
