package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.ConnectorTestRun;
import com.atlas.enterprise.configuration.port.ConnectorTestRepository;
import org.springframework.stereotype.Component;

@Component
public class ConnectorReleaseGuard implements ConfigurationReleaseGuard {
    private final ConnectorTestRepository tests;
    private final ConnectorConfigurationCodec codec;

    public ConnectorReleaseGuard(
        ConnectorTestRepository tests,
        ConnectorConfigurationCodec codec
    ) {
        this.tests = tests;
        this.codec = codec;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.DATA_SOURCE
            || category == ConfigurationCategory.SEARCH
            || category == ConfigurationCategory.MODEL;
    }

    @Override
    public void checkPublish(ConfigurationVersion version, String environment) {
        if (!codec.isConnectorDocument(version.valueJson())) {
            throw new ConfigurationConflictException(
                "Legacy generic connector documents cannot be published; migrate to atlas-connector.v1"
            );
        }
        var connector = codec.parse(version.valueJson());
        if (connector.category() == ConfigurationCategory.DATA_SOURCE) {
            throw new ConfigurationConflictException(
                "V1 data-source versions are connection-test records only; task execution still uses the service Elasticsearch runtime configuration"
            );
        }
        if (!connector.enabled()) return;
        ConnectorTestRun test = tests.findLatest(version.versionId())
            .orElseThrow(() -> new ConfigurationConflictException(
                "Enabled connector must pass a connection test before publishing"
            ));
        if (!version.checksum().equals(test.versionChecksum())
            || test.status() != ConnectorTestRun.Status.PASSED) {
            throw new ConfigurationConflictException(
                "Latest successful connection test does not match the connector draft"
            );
        }
    }
}
