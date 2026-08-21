package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;

public interface ConfigurationContentValidator {
    boolean supports(ConfigurationCategory category);

    String validate(String valueJson);

    default String validate(ConfigurationCategory category, String valueJson) {
        return validate(valueJson);
    }
}
