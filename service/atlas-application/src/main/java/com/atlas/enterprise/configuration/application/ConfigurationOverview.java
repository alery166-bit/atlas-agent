package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationBinding;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import java.util.List;

public record ConfigurationOverview(
    ConfigurationDefinition definition,
    List<ConfigurationVersion> versions,
    ConfigurationBinding binding
) {
    public ConfigurationOverview {
        versions = List.copyOf(versions);
    }
}
