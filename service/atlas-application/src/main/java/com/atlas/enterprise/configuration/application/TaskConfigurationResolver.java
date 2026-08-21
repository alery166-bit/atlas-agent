package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.TaskConfigurationSnapshot;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TaskConfigurationResolver {
    private final ConfigurationRepository configurations;
    private final ObjectMapper objectMapper;
    private final String environment;

    public TaskConfigurationResolver(
        ConfigurationRepository configurations,
        ObjectMapper objectMapper,
        @Value("${atlas.environment:DEV}") String environment
    ) {
        this.configurations = configurations;
        this.objectMapper = objectMapper;
        this.environment = environment.trim().toUpperCase();
    }

    public List<ResolvedConfiguration> resolve(UUID taskId, ConfigurationCategory category) {
        if (taskId == null) return List.of();
        TaskConfigurationSnapshot snapshot = configurations
            .findTaskSnapshot(taskId, environment).orElse(null);
        if (snapshot == null) return List.of();
        try {
            JsonNode manifest = objectMapper.readTree(snapshot.manifestJson());
            List<ResolvedConfiguration> values = new ArrayList<>();
            List<ConfigurationDefinition> definitions = configurations.findDefinitions();
            for (JsonNode item : manifest) {
                UUID versionId = UUID.fromString(item.path("version_id").asText());
                ConfigurationVersion version = configurations.findVersion(versionId).orElse(null);
                if (version == null) continue;
                ConfigurationDefinition definition = definitions.stream()
                    .filter(value -> value.configId().equals(version.configId()))
                    .findFirst().orElse(null);
                if (definition == null || definition.category() != category) continue;
                values.add(new ResolvedConfiguration(definition, version));
            }
            return List.copyOf(values);
        } catch (Exception exception) {
            throw new IllegalStateException("Task configuration snapshot is invalid", exception);
        }
    }

    public record ResolvedConfiguration(
        ConfigurationDefinition definition,
        ConfigurationVersion version
    ) {}
}
