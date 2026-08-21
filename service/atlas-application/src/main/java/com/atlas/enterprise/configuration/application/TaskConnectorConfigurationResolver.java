package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskConnectorConfigurationResolver {
    private final TaskConfigurationResolver configurations;
    private final ConnectorConfigurationCodec codec;

    public TaskConnectorConfigurationResolver(
        TaskConfigurationResolver configurations,
        ConnectorConfigurationCodec codec
    ) {
        this.configurations = configurations;
        this.codec = codec;
    }

    public List<ResolvedConnector> resolve(UUID taskId, ConfigurationCategory category) {
        List<ResolvedConnector> values = new ArrayList<>();
        for (TaskConfigurationResolver.ResolvedConfiguration item
            : configurations.resolve(taskId, category)) {
                if (!codec.isConnectorDocument(item.version().valueJson())) continue;
                ConnectorConfigurationCodec.ConnectorDefinition connector = codec.parse(
                    item.version().valueJson()
                );
                values.add(new ResolvedConnector(
                    item.definition().configKey(), item.version().versionNo(),
                    item.version().checksum(), connector
                ));
        }
        return List.copyOf(values);
    }

    public record ResolvedConnector(
        String configKey,
        int versionNo,
        String checksum,
        ConnectorConfigurationCodec.ConnectorDefinition definition
    ) {}
}
