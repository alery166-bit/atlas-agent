package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.intelligence.search")
public class SearchProviderProperties {
    private List<Provider> providers = new ArrayList<>();

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null
            ? new ArrayList<>()
            : new ArrayList<>(providers);
    }

    public List<Provider> enabledProviders() {
        List<Provider> enabled = providers.stream()
            .filter(Provider::isEnabled)
            .toList();
        validate(enabled);
        return enabled;
    }

    private static void validate(List<Provider> providers) {
        Set<String> names = new HashSet<>();
        for (Provider provider : providers) {
            provider.validate();
            String normalized = provider.getName().trim().toLowerCase(Locale.ROOT);
            if (!names.add(normalized)) {
                throw new IllegalStateException(
                    "Duplicate public-search provider name: " + provider.getName()
                );
            }
        }
    }

    public static class Provider {
        public enum ProviderType {
            STANDARD,
            TAVILY
        }

        private String name;
        private boolean enabled;
        private ProviderType providerType = ProviderType.STANDARD;
        private ProviderCapabilities.ProviderMode mode =
            ProviderCapabilities.ProviderMode.SEARCH_ENGINE;
        private URI baseUrl;
        private String path = "/search";
        private String queryParameter = "q";
        private String limitParameter = "count";
        private int maxResults = 10;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(15);
        private long minimumIntervalMs;
        private int maxAttempts = 3;
        private Duration retryBackoff = Duration.ofMillis(250);
        private int circuitBreakerFailureThreshold = 5;
        private Duration circuitBreakerOpenDuration = Duration.ofSeconds(30);
        private boolean required = true;
        private boolean returnsAccessibleCitations = true;
        private boolean requireApiKey = true;
        private String apiKey;
        private String apiKeyHeader = "Authorization";
        private String apiKeyPrefix = "Bearer ";
        private String searchDepth = "basic";
        private String topic = "general";

        public void validate() {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                    "Enabled public-search provider must have a name"
                );
            }
            if (mode == ProviderCapabilities.ProviderMode.FIXTURE
                || mode == ProviderCapabilities.ProviderMode.UNCONFIGURED) {
                throw new IllegalStateException(
                    "Configured provider mode must be SEARCH_ENGINE or LLM_SEARCH"
                );
            }
            if (baseUrl == null
                || (!"http".equalsIgnoreCase(baseUrl.getScheme())
                    && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
                throw new IllegalStateException(
                    "Provider " + name + " must use an HTTP(S) base URL"
                );
            }
            if (path == null || !path.startsWith("/")) {
                throw new IllegalStateException(
                    "Provider " + name + " path must start with '/'"
                );
            }
            if (providerType == ProviderType.TAVILY) {
                if (!Set.of("basic", "advanced", "fast", "ultra-fast")
                    .contains(searchDepth)) {
                    throw new IllegalStateException(
                        "Provider " + name + " has unsupported Tavily search-depth"
                    );
                }
                if (!Set.of("general", "news", "finance").contains(topic)) {
                    throw new IllegalStateException(
                        "Provider " + name + " has unsupported Tavily topic"
                    );
                }
            }
            if (queryParameter == null || queryParameter.isBlank()
                || limitParameter == null || limitParameter.isBlank()) {
                throw new IllegalStateException(
                    "Provider " + name + " query and limit parameters are required"
                );
            }
            if (maxResults < 1 || maxResults > 50) {
                throw new IllegalStateException(
                    "Provider " + name + " max-results must be between 1 and 50"
                );
            }
            validateDuration(connectTimeout, "connect-timeout");
            validateDuration(requestTimeout, "request-timeout");
            if (minimumIntervalMs < 0 || minimumIntervalMs > 60_000) {
                throw new IllegalStateException(
                    "Provider " + name
                        + " minimum-interval-ms must be between 0 and 60000"
                );
            }
            if (maxAttempts < 1 || maxAttempts > 5) {
                throw new IllegalStateException(
                    "Provider " + name
                        + " max-attempts must be between 1 and 5"
                );
            }
            validateNonNegativeDuration(
                retryBackoff,
                "retry-backoff",
                Duration.ofSeconds(5)
            );
            if (
                circuitBreakerFailureThreshold < 1
                    || circuitBreakerFailureThreshold > 100
            ) {
                throw new IllegalStateException(
                    "Provider " + name
                        + " circuit-breaker-failure-threshold"
                        + " must be between 1 and 100"
                );
            }
            validateDuration(
                circuitBreakerOpenDuration,
                "circuit-breaker-open-duration"
            );
            if (requireApiKey && (apiKey == null || apiKey.isBlank())) {
                throw new IllegalStateException(
                    "Provider " + name + " requires an API key"
                );
            }
            if (apiKey != null && !apiKey.isBlank()
                && (apiKeyHeader == null || apiKeyHeader.isBlank())) {
                throw new IllegalStateException(
                    "Provider " + name + " API key header is required"
                );
            }
        }

        private void validateDuration(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalStateException(
                    "Provider " + name + " " + field
                        + " must be greater than 0 and at most 2 minutes"
                );
            }
        }

        private void validateNonNegativeDuration(
            Duration value,
            String field,
            Duration maximum
        ) {
            if (
                value == null
                    || value.isNegative()
                    || value.compareTo(maximum) > 0
            ) {
                throw new IllegalStateException(
                    "Provider " + name + " " + field
                        + " must be between 0 and " + maximum
                );
            }
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public ProviderType getProviderType() {
            return providerType;
        }

        public void setProviderType(ProviderType providerType) {
            this.providerType = providerType == null
                ? ProviderType.STANDARD
                : providerType;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ProviderCapabilities.ProviderMode getMode() {
            return mode;
        }

        public void setMode(ProviderCapabilities.ProviderMode mode) {
            this.mode = mode;
        }

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getQueryParameter() {
            return queryParameter;
        }

        public void setQueryParameter(String queryParameter) {
            this.queryParameter = queryParameter;
        }

        public String getLimitParameter() {
            return limitParameter;
        }

        public void setLimitParameter(String limitParameter) {
            this.limitParameter = limitParameter;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public long getMinimumIntervalMs() {
            return minimumIntervalMs;
        }

        public void setMinimumIntervalMs(long minimumIntervalMs) {
            this.minimumIntervalMs = minimumIntervalMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public int getCircuitBreakerFailureThreshold() {
            return circuitBreakerFailureThreshold;
        }

        public void setCircuitBreakerFailureThreshold(
            int circuitBreakerFailureThreshold
        ) {
            this.circuitBreakerFailureThreshold =
                circuitBreakerFailureThreshold;
        }

        public Duration getCircuitBreakerOpenDuration() {
            return circuitBreakerOpenDuration;
        }

        public void setCircuitBreakerOpenDuration(
            Duration circuitBreakerOpenDuration
        ) {
            this.circuitBreakerOpenDuration = circuitBreakerOpenDuration;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public boolean isReturnsAccessibleCitations() {
            return returnsAccessibleCitations;
        }

        public void setReturnsAccessibleCitations(
            boolean returnsAccessibleCitations
        ) {
            this.returnsAccessibleCitations = returnsAccessibleCitations;
        }

        public boolean isRequireApiKey() {
            return requireApiKey;
        }

        public void setRequireApiKey(boolean requireApiKey) {
            this.requireApiKey = requireApiKey;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiKeyHeader() {
            return apiKeyHeader;
        }

        public void setApiKeyHeader(String apiKeyHeader) {
            this.apiKeyHeader = apiKeyHeader;
        }

        public String getApiKeyPrefix() {
            return apiKeyPrefix;
        }

        public void setApiKeyPrefix(String apiKeyPrefix) {
            this.apiKeyPrefix = apiKeyPrefix == null ? "" : apiKeyPrefix;
        }

        public String getSearchDepth() {
            return searchDepth;
        }

        public void setSearchDepth(String searchDepth) {
            this.searchDepth = searchDepth == null
                ? "basic"
                : searchDepth.trim().toLowerCase(Locale.ROOT);
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic == null
                ? "general"
                : topic.trim().toLowerCase(Locale.ROOT);
        }
    }
}
