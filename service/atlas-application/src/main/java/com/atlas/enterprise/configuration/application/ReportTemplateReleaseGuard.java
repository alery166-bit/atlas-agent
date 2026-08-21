package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import org.springframework.stereotype.Component;

@Component
public class ReportTemplateReleaseGuard implements ConfigurationReleaseGuard {
    private final ReportTemplateConfigurationCodec codec;
    private final ConfigurationRepository configurations;

    public ReportTemplateReleaseGuard(
        ReportTemplateConfigurationCodec codec,
        ConfigurationRepository configurations
    ) {
        this.codec = codec;
        this.configurations = configurations;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.REPORT_TEMPLATE;
    }

    @Override
    public void checkPublish(ConfigurationVersion version, String environment) {
        var template = codec.parse(version.valueJson());
        if (!template.enabled()) return;
        String normalizedEnvironment = environment.trim().toUpperCase();
        for (var dependency : template.dependencies()) {
            if (!dependency.required()) continue;
            var definition = configurations.findDefinition(dependency.configKey())
                .orElseThrow(() -> new ConfigurationConflictException(
                    "Required template dependency is not registered: " + dependency.configKey()
                ));
            if (definition.category() != dependency.category()) {
                throw new ConfigurationConflictException(
                    "Template dependency category does not match: " + dependency.configKey()
                );
            }
            if (configurations.findBinding(definition.configId(), normalizedEnvironment).isEmpty()) {
                throw new ConfigurationConflictException(
                    "Required template dependency is not published in " + environment + ": "
                        + dependency.configKey()
                );
            }
        }
    }
}
