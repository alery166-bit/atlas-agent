package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.application.ConfigurationContentValidator;
import org.springframework.stereotype.Component;

@Component
public class RiskRulePolicyContentValidator implements ConfigurationContentValidator {
    private final RiskRulePolicyCodec codec;

    public RiskRulePolicyContentValidator(RiskRulePolicyCodec codec) {
        this.codec = codec;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.RULES;
    }

    @Override
    public String validate(String valueJson) {
        RiskRulePolicyCodec.ParsedPolicy policy = codec.parse(valueJson, "validation");
        return "Risk policy schema passed; golden replay gate requires at least "
            + policy.replayGate().minimumSamples() + " samples";
    }
}
