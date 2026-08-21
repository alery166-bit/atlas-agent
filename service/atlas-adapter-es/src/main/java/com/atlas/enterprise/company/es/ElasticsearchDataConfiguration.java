package com.atlas.enterprise.company.es;

import com.atlas.enterprise.company.port.CompanyDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(ElasticsearchDataProperties.class)
@ConditionalOnProperty(name = "atlas.data.provider", havingValue = "es")
public class ElasticsearchDataConfiguration {
    @Bean
    @Primary
    CompanyDataProvider elasticsearchCompanyDataProvider(
        ElasticsearchDataProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        properties.validate();
        return new ElasticsearchCompanyDataProvider(properties, objectMapper, clock);
    }
}
