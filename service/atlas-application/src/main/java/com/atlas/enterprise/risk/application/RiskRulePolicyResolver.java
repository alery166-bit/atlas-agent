package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.TaskConfigurationSnapshot;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import com.atlas.enterprise.risk.RiskScoringPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RiskRulePolicyResolver {
    private final ConfigurationRepository configurations;
    private final RiskRulePolicyCodec codec;
    private final ObjectMapper objectMapper;
    private final String environment;

    public RiskRulePolicyResolver(
        ConfigurationRepository configurations,
        RiskRulePolicyCodec codec,
        ObjectMapper objectMapper,
        @Value("${atlas.environment:DEV}") String environment
    ) {
        this.configurations = configurations;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.environment = environment.trim().toUpperCase();
    }

    public RiskScoringPolicy resolve(UUID taskId) {
        TaskConfigurationSnapshot snapshot = configurations
            .findTaskSnapshot(taskId, environment).orElse(null);
        if (snapshot == null) {
            return RiskScoringPolicy.defaultPolicy();
        }
        try {
            JsonNode manifest = objectMapper.readTree(snapshot.manifestJson());
            for (JsonNode item : manifest) {
                if (RiskRulePolicyCodec.CONFIG_KEY.equals(item.path("config_key").asText())) {
                    UUID versionId = UUID.fromString(item.path("version_id").asText());
                    ConfigurationVersion version = configurations.findVersion(versionId)
                        .orElse(null);
                    if (version != null) {
                        String label = RiskRulePolicyCodec.CONFIG_KEY + "/v" + version.versionNo()
                            + "@" + version.checksum().substring(0, 8);
                        return codec.parse(version.valueJson(), label).runtime();
                    }
                }
            }
            return RiskScoringPolicy.defaultPolicy();
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Task risk rule configuration snapshot is invalid", exception
            );
        }
    }
}
