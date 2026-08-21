package com.atlas.enterprise.company.refresh;

import com.atlas.enterprise.company.port.CompanyRefreshPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(XlbCompanyRefreshProperties.class)
public class XlbCompanyRefreshConfiguration {
    @Bean
    CompanyRefreshPort xlbCompanyRefreshPort(
        XlbCompanyRefreshProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        properties.validate();
        return new XlbCompanyRefreshAdapter(properties, objectMapper, clock);
    }
}
