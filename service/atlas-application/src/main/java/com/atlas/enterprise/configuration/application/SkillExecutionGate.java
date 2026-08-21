package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SkillExecutionGate {
    private final TaskConfigurationResolver configurations;
    private final SkillConfigurationCodec codec;

    public SkillExecutionGate(
        TaskConfigurationResolver configurations,
        SkillConfigurationCodec codec
    ) {
        this.configurations = configurations;
        this.codec = codec;
    }

    public void requireEnabled(UUID taskId, String skillKey) {
        var configured = configurations.resolve(taskId, ConfigurationCategory.SKILL).stream()
            .filter(item -> codec.isSkillDocument(item.version().valueJson()))
            .map(item -> codec.parse(item.version().valueJson()))
            .filter(item -> item.skillKey().equals(skillKey))
            .findFirst();
        if (configured.isPresent() && !configured.get().enabled()) {
            throw new SkillDisabledException(skillKey);
        }
    }
}
