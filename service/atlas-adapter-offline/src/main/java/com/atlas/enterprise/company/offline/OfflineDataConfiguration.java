package com.atlas.enterprise.company.offline;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OfflineDataProperties.class)
class OfflineDataConfiguration {
}
