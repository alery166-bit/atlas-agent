package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.port.PublicSearchProvider;
import com.atlas.enterprise.intelligence.port.PublicSearchProviderRegistry;
import com.atlas.enterprise.intelligence.port.EvidenceContentFetcher;
import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.application.TaskConnectorConfigurationResolver;
import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    SearchProviderProperties.class,
    EvidenceContentProperties.class
})
public class SearchProviderConfiguration {
    @Bean
    PublicSearchProviderRegistry publicSearchProviderRegistry(
        List<PublicSearchProvider> fixtureProviders,
        SearchProviderProperties properties,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        TaskConnectorConfigurationResolver configuredConnectors
    ) {
        List<PublicSearchProvider> providers = new ArrayList<>(fixtureProviders);
        for (SearchProviderProperties.Provider provider
            : properties.enabledProviders()) {
            providers.add(new StandardHttpSearchProvider(
                provider,
                objectMapper,
                meterRegistry
            ));
        }
        if (providers.isEmpty()) {
            providers.add(new UnconfiguredPublicSearchProvider());
        }
        List<PublicSearchProvider> frozen = List.copyOf(providers);
        ConcurrentHashMap<String, PublicSearchProvider> configured = new ConcurrentHashMap<>();
        return taskId -> {
            List<TaskConnectorConfigurationResolver.ResolvedConnector> taskConfigurations =
                configuredConnectors.resolve(taskId, ConfigurationCategory.SEARCH);
            if (taskConfigurations.isEmpty()) return frozen;
            List<PublicSearchProvider> taskProviders = taskConfigurations.stream()
                .filter(item -> item.definition().enabled())
                .map(item -> configured.computeIfAbsent(item.checksum(), ignored ->
                    new StandardHttpSearchProvider(
                        provider(item), objectMapper, meterRegistry
                    )
                )).toList();
            return taskProviders.isEmpty()
                ? List.of(new UnconfiguredPublicSearchProvider())
                : taskProviders;
        };
    }

    @Bean
    @ConditionalOnMissingBean(EvidenceContentFetcher.class)
    EvidenceContentFetcher evidenceContentFetcher(
        EvidenceContentProperties properties
    ) {
        return properties.isEnabled()
            ? new StandardHttpEvidenceContentFetcher(properties)
            : new UnconfiguredEvidenceContentFetcher();
    }

    private static SearchProviderProperties.Provider provider(
        TaskConnectorConfigurationResolver.ResolvedConnector configured
    ) {
        var source = configured.definition();
        SearchProviderProperties.Provider value = new SearchProviderProperties.Provider();
        value.setName(configured.configKey() + "/v" + configured.versionNo());
        value.setEnabled(true);
        value.setProviderType(SearchProviderProperties.Provider.ProviderType.TAVILY);
        value.setMode(ProviderCapabilities.ProviderMode.SEARCH_ENGINE);
        value.setBaseUrl(source.baseUri());
        value.setPath(source.path());
        value.setMaxResults(source.settings().path("max_results").asInt(10));
        value.setConnectTimeout(source.connectTimeout());
        value.setRequestTimeout(source.requestTimeout());
        value.setMaxAttempts(source.maxAttempts());
        value.setRetryBackoff(Duration.ofMillis(source.backoffMs()));
        value.setRequired(source.required());
        value.setReturnsAccessibleCitations(true);
        value.setRequireApiKey(true);
        value.setApiKey(resolveCredential(source.credentialRef()));
        value.setSearchDepth(source.settings().path("search_depth").asText("basic"));
        value.setTopic(source.settings().path("topic").asText("general"));
        return value;
    }

    private static String resolveCredential(String reference) {
        if (reference == null || !reference.startsWith("env:")) {
            throw new IllegalStateException("Configured search credential backend is unavailable");
        }
        String name = reference.substring(4);
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configured search credential reference is unavailable");
        }
        return value;
    }
}
