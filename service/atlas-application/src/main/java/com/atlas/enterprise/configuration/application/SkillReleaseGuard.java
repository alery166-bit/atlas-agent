package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import org.springframework.stereotype.Component;

@Component
public class SkillReleaseGuard implements ConfigurationReleaseGuard {
    private final SkillConfigurationCodec codec;
    private final ConfigurationRepository configurations;

    public SkillReleaseGuard(
        SkillConfigurationCodec codec,
        ConfigurationRepository configurations
    ) {
        this.codec = codec;
        this.configurations = configurations;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.SKILL;
    }

    @Override
    public void checkPublish(ConfigurationVersion version, String environment) {
        var skill = codec.parse(version.valueJson());
        if (!skill.enabled()) return;
        for (var dependency : skill.dependencies()) {
            if (!dependency.required()) continue;
            var definition = configurations.findDefinition(dependency.configKey())
                .orElseThrow(() -> new ConfigurationConflictException(
                    "Required dependency is not registered: " + dependency.configKey()
                ));
            if (definition.category() != dependency.category()) {
                throw new ConfigurationConflictException(
                    "Dependency category does not match: " + dependency.configKey()
                );
            }
            if (configurations.findBinding(definition.configId(), environment.trim().toUpperCase()).isEmpty()) {
                throw new ConfigurationConflictException(
                    "Required dependency is not published in " + environment + ": "
                        + dependency.configKey()
                );
            }
        }
    }
}
