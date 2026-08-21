package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;

public interface ConfigurationReleaseGuard {
    boolean supports(ConfigurationCategory category);

    void checkPublish(ConfigurationVersion version, String environment);
}
