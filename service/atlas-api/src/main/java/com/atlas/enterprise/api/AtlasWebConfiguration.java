package com.atlas.enterprise.api;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AtlasWebConfiguration implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    public AtlasWebConfiguration(
        @Value(
            "${atlas.web.allowed-origins:http://localhost:3000,http://localhost:5173}"
        ) List<String> allowedOrigins
    ) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders(
                "Content-Type",
                "Idempotency-Key",
                "X-Operator-Id",
                "X-Worker-Id",
                "Last-Event-ID"
            )
            .exposedHeaders(
                "Location",
                "X-Trace-Id",
                "X-Content-SHA256",
                "Content-Disposition"
            )
            .allowCredentials(false)
            .maxAge(3600);
        registry.addMapping("/actuator/health")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "OPTIONS")
            .allowCredentials(false)
            .maxAge(3600);
    }
}
