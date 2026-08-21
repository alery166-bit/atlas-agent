package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskLevelTest {
    @Test
    void appliesConfirmedBoundarySemanticsWithoutRounding() {
        assertEquals(RiskLevel.LOW, RiskLevel.from(new BigDecimal("1.9999")));
        assertEquals(RiskLevel.MEDIUM_LOW, RiskLevel.from(new BigDecimal("2")));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.from(new BigDecimal("4")));
        assertEquals(RiskLevel.MEDIUM_HIGH, RiskLevel.from(new BigDecimal("6")));
        assertEquals(RiskLevel.HIGH, RiskLevel.from(new BigDecimal("8")));
        assertEquals(RiskLevel.HIGH, RiskLevel.from(new BigDecimal("10")));
    }
}
