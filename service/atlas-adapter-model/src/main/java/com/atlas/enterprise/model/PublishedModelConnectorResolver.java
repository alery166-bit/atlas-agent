package com.atlas.enterprise.model;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.application.ConfigurationApplicationService;
import com.atlas.enterprise.configuration.application.ConnectorConfigurationCodec;
import com.atlas.enterprise.configuration.application.TaskConnectorConfigurationResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class PublishedModelConnectorResolver {
    private final ConfigurationApplicationService configurations;
    private final TaskConnectorConfigurationResolver taskConnectors;
    private final ConnectorConfigurationCodec codec;
    private final String environment;

    PublishedModelConnectorResolver(
        ConfigurationApplicationService configurations,
        TaskConnectorConfigurationResolver taskConnectors,
        ConnectorConfigurationCodec codec,
        @Value("${atlas.environment:DEV}") String environment
    ) {
        this.configurations = configurations;
        this.taskConnectors = taskConnectors;
        this.codec = codec;
        this.environment = environment == null ? "DEV" : environment.trim().toUpperCase();
    }

    Optional<ResolvedModel> active() {
        return configurations.list(environment).stream()
            .filter(item -> item.definition().category() == ConfigurationCategory.MODEL)
            .filter(item -> item.binding() != null)
            .flatMap(item -> item.versions().stream()
                .filter(version -> version.versionId().equals(item.binding().activeVersionId()))
                .map(version -> resolve(item.definition().configKey(), version)))
            .filter(model -> model.definition().enabled())
            .findFirst();
    }

    Optional<ResolvedModel> forTask(UUID taskId) {
        return taskConnectors.resolve(taskId, ConfigurationCategory.MODEL).stream()
            .filter(item -> item.definition().enabled())
            .map(item -> new ResolvedModel(
                item.configKey(), item.versionNo(), item.checksum(),
                item.definition(), credential(item.definition().credentialRef())
            ))
            .findFirst();
    }

    private ResolvedModel resolve(String key, ConfigurationVersion version) {
        ConnectorConfigurationCodec.ConnectorDefinition definition = codec.parse(
            version.valueJson()
        );
        return new ResolvedModel(
            key, version.versionNo(), version.checksum(), definition,
            credential(definition.credentialRef())
        );
    }

    private static String credential(String reference) {
        if (reference == null || !reference.startsWith("env:")) {
            throw new IllegalStateException("Model credential must use an env: reference");
        }
        String name = reference.substring(4);
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Published model credential is unavailable");
        }
        return value;
    }

    record ResolvedModel(
        String configKey,
        int versionNo,
        String checksum,
        ConnectorConfigurationCodec.ConnectorDefinition definition,
        String credential
    ) {}
}
