package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import org.springframework.stereotype.Component;

@Component
public class ConnectorConfigurationValidator implements ConfigurationContentValidator {
    private final ConnectorConfigurationCodec codec;

    public ConnectorConfigurationValidator(ConnectorConfigurationCodec codec) {
        this.codec = codec;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.DATA_SOURCE
            || category == ConfigurationCategory.SEARCH
            || category == ConfigurationCategory.MODEL;
    }

    @Override
    public String validate(String valueJson) {
        requireManagedDocument(valueJson);
        ConnectorConfigurationCodec.ConnectorDefinition definition = codec.parse(valueJson);
        return validationMessage(definition);
    }

    @Override
    public String validate(ConfigurationCategory category, String valueJson) {
        requireManagedDocument(valueJson);
        ConnectorConfigurationCodec.ConnectorDefinition definition = codec.parse(valueJson);
        if (definition.category() != category) {
            throw new IllegalArgumentException(
                "Connector document category does not match configuration category"
            );
        }
        return validationMessage(definition);
    }

    private void requireManagedDocument(String valueJson) {
        if (!codec.isConnectorDocument(valueJson)) {
            throw new IllegalArgumentException(
                "Connector categories require an atlas-connector.v1 document"
            );
        }
    }

    private static String validationMessage(
        ConnectorConfigurationCodec.ConnectorDefinition definition
    ) {
        if (definition.category() == ConfigurationCategory.SEARCH
            || definition.category() == ConfigurationCategory.MODEL) {
            String capability = definition.category() == ConfigurationCategory.SEARCH
                ? "search"
                : "model";
            return "Runtime " + capability + " connector schema passed: " + definition.kind()
                + "; matching successful connection test required before publish";
        }
        return "Connection-test record schema passed: " + definition.kind()
            + "; this category is not wired to V1 task execution and cannot be published";
    }
}
