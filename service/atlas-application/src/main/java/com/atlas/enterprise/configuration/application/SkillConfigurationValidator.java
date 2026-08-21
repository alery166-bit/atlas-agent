package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import org.springframework.stereotype.Component;

@Component
public class SkillConfigurationValidator implements ConfigurationContentValidator {
    private final SkillConfigurationCodec codec;

    public SkillConfigurationValidator(SkillConfigurationCodec codec) {
        this.codec = codec;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.SKILL;
    }

    @Override
    public String validate(String valueJson) {
        var skill = codec.parse(valueJson);
        return "Skill contract passed: " + skill.skillKey()
            + "; only enabled and release dependencies are configurable in V1";
    }
}
