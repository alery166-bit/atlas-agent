package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.risk.RiskScoreEngine;
import com.atlas.enterprise.risk.LegacyRiskScoreEngineV1;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RiskScoreConfiguration {
    @Bean
    RiskScoreEngine riskScoreEngine() {
        return new LegacyRiskScoreEngineV1();
    }
}
