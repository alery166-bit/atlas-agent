package com.atlas.enterprise.intelligence.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchProviderPropertiesTest {
    @Test
    void rejectsDuplicateEnabledProviderNames() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviders(List.of(
            valid("Search-One"),
            valid("search-one")
        ));

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            properties::enabledProviders
        );

        assertEquals(
            "Duplicate public-search provider name: search-one",
            error.getMessage()
        );
    }

    @Test
    void ignoresDisabledProviderWithMissingCredentials() {
        SearchProviderProperties properties = new SearchProviderProperties();
        SearchProviderProperties.Provider disabled =
            new SearchProviderProperties.Provider();
        disabled.setName("disabled");
        disabled.setEnabled(false);
        properties.setProviders(List.of(disabled));

        assertEquals(List.of(), properties.enabledProviders());
    }

    @Test
    void rejectsEnabledProviderWithoutApiKey() {
        SearchProviderProperties.Provider provider = valid("missing-secret");
        provider.setApiKey("");

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            provider::validate
        );

        assertEquals(
            "Provider missing-secret requires an API key",
            error.getMessage()
        );
    }

    @Test
    void rejectsUnsafeResilienceConfiguration() {
        SearchProviderProperties.Provider attempts = valid("attempts");
        attempts.setMaxAttempts(6);
        SearchProviderProperties.Provider circuit = valid("circuit");
        circuit.setCircuitBreakerFailureThreshold(0);
        SearchProviderProperties.Provider backoff = valid("backoff");
        backoff.setRetryBackoff(Duration.ofSeconds(6));

        assertThrows(IllegalStateException.class, attempts::validate);
        assertThrows(IllegalStateException.class, circuit::validate);
        assertThrows(IllegalStateException.class, backoff::validate);
    }

    @Test
    void rejectsUnsupportedTavilySearchMode() {
        SearchProviderProperties.Provider provider = valid("tavily");
        provider.setProviderType(
            SearchProviderProperties.Provider.ProviderType.TAVILY
        );
        provider.setSearchDepth("expensive-magic");

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            provider::validate
        );

        assertEquals(
            "Provider tavily has unsupported Tavily search-depth",
            error.getMessage()
        );
    }

    private static SearchProviderProperties.Provider valid(String name) {
        SearchProviderProperties.Provider provider =
            new SearchProviderProperties.Provider();
        provider.setName(name);
        provider.setEnabled(true);
        provider.setBaseUrl(URI.create("https://search.example.test"));
        provider.setApiKey("test-secret");
        return provider;
    }
}
