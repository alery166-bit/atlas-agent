package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchBatchStatus;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.intelligence.SearchResult;
import com.atlas.enterprise.intelligence.port.PublicSearchProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class StandardHttpSearchProvider implements PublicSearchProvider {
    private static final int MAX_SNIPPET_LENGTH = 6_000;
    private static final int MAX_RAW_CONTENT_LENGTH = 200_000;

    private final SearchProviderProperties.Provider properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;
    private final AtomicLong nextRequestAt = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntil = new AtomicLong();

    public StandardHttpSearchProvider(
        SearchProviderProperties.Provider properties,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        properties.validate();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        Gauge.builder(
            "atlas.search.circuit.state",
            this,
            StandardHttpSearchProvider::circuitState
        )
            .description("Search provider circuit state: 0 closed, 1 open")
            .tag("provider", properties.getName())
            .register(meterRegistry);
    }

    @Override
    public SearchBatch search(SearchRequest request) {
        Instant searchedAt = Instant.now();
        long startedAt = System.nanoTime();
        if (!acquireCircuitPermission()) {
            SearchBatch batch = SearchBatch.failed(
                properties.getName(),
                "CIRCUIT_OPEN",
                "Search provider circuit is open",
                searchedAt
            );
            recordCompletion(batch, startedAt);
            return batch;
        }

        SearchAttempt lastAttempt = null;
        for (
            int attemptNumber = 1;
            attemptNumber <= properties.getMaxAttempts();
            attemptNumber++
        ) {
            lastAttempt = attempt(request, searchedAt);
            recordUpstreamCall(lastAttempt.batch());
            if (
                !lastAttempt.retryable()
                    || attemptNumber == properties.getMaxAttempts()
            ) {
                break;
            }
            meterRegistry.counter(
                "atlas.search.retries",
                "provider",
                properties.getName(),
                "reason",
                lastAttempt.retryReason()
            ).increment();
            if (!awaitRetryBackoff(attemptNumber)) {
                lastAttempt = new SearchAttempt(
                    interrupted(searchedAt),
                    false,
                    "interrupted"
                );
                break;
            }
        }

        SearchBatch result = lastAttempt == null
            ? SearchBatch.failed(
                properties.getName(),
                "SEARCH_NOT_EXECUTED",
                "Search request was not executed",
                searchedAt
            )
            : lastAttempt.batch();
        if (isSuccess(result)) {
            closeCircuit();
        } else if (!"SEARCH_INTERRUPTED".equals(result.failureCode())) {
            recordCircuitFailure();
        } else {
            releaseHalfOpenProbe();
        }
        recordCompletion(result, startedAt);
        return result;
    }

    private SearchAttempt attempt(
        SearchRequest request,
        Instant searchedAt
    ) {
        try {
            awaitRateLimit();
            HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(request))
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json");
            if (isTavily()) {
                builder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        tavilyRequestBody(request),
                        StandardCharsets.UTF_8
                    ));
            } else {
                builder.GET();
            }
            if (properties.getApiKey() != null
                && !properties.getApiKey().isBlank()) {
                builder.header(
                    properties.getApiKeyHeader(),
                    properties.getApiKeyPrefix() + properties.getApiKey()
                );
            }
            HttpResponse<String> response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                SearchBatch failure = failedForStatus(
                    response.statusCode(),
                    searchedAt
                );
                return new SearchAttempt(
                    failure,
                    response.statusCode() == 429
                        || response.statusCode() >= 500,
                    response.statusCode() == 429
                        ? "rate_limited"
                        : "upstream_http"
                );
            }
            return new SearchAttempt(
                parseResponse(response.body(), searchedAt),
                false,
                "none"
            );
        } catch (HttpTimeoutException exception) {
            return new SearchAttempt(
                SearchBatch.failed(
                    properties.getName(),
                    "UPSTREAM_TIMEOUT",
                    "Search request timed out",
                    searchedAt
                ),
                true,
                "timeout"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SearchAttempt(
                interrupted(searchedAt),
                false,
                "interrupted"
            );
        } catch (IOException exception) {
            return new SearchAttempt(
                SearchBatch.failed(
                    properties.getName(),
                    "SEARCH_IO_ERROR",
                    exception.getClass().getSimpleName(),
                    searchedAt
                ),
                true,
                "io_error"
            );
        } catch (RuntimeException exception) {
            return new SearchAttempt(
                SearchBatch.failed(
                    properties.getName(),
                    "PROVIDER_RESPONSE_INVALID",
                    exception.getMessage(),
                    searchedAt
                ),
                false,
                "invalid_response"
            );
        }
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
            properties.getName(),
            properties.getMode(),
            properties.isReturnsAccessibleCitations(),
            properties.isRequired()
        );
    }

    private URI buildUri(SearchRequest request) {
        if (isTavily()) {
            return properties.getBaseUrl().resolve(properties.getPath());
        }
        String separator = properties.getPath().contains("?") ? "&" : "?";
        String query = encode(properties.getQueryParameter()) + "="
            + encode(request.query()) + "&"
            + encode(properties.getLimitParameter()) + "="
            + properties.getMaxResults();
        return properties.getBaseUrl().resolve(
            properties.getPath() + separator + query
        );
    }

    private String tavilyRequestBody(SearchRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", request.query());
        payload.put("search_depth", properties.getSearchDepth());
        payload.put("topic", request.topic());
        payload.put("max_results", properties.getMaxResults());
        payload.put("include_answer", false);
        payload.put("include_raw_content", request.includeRawContent() ? "text" : false);
        if (!request.includeDomains().isEmpty()) {
            payload.put("include_domains", request.includeDomains());
        }
        payload.put("include_images", false);
        payload.put("auto_parameters", false);
        return objectMapper.writeValueAsString(payload);
    }

    private SearchBatch parseResponse(String body, Instant searchedAt)
        throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode items = root.path("results");
        if (!items.isArray()) {
            throw new IllegalArgumentException(
                "Response must contain a results array"
            );
        }
        List<SearchResult> results = new ArrayList<>();
        int fallbackRank = 1;
        for (JsonNode item : items) {
            if (results.size() >= properties.getMaxResults()) {
                break;
            }
            String title = text(item, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            int rank = item.path("rank").canConvertToInt()
                ? item.path("rank").asInt()
                : fallbackRank;
            if (rank < 1) {
                rank = fallbackRank;
            }
            results.add(new SearchResult(
                text(item, "id"),
                title,
                text(item, "url"),
                truncate(firstText(item, "snippet", "content"), MAX_SNIPPET_LENGTH),
                truncate(text(item, "raw_content"), MAX_RAW_CONTENT_LENGTH),
                parseInstant(firstText(
                    item,
                    "published_at",
                    "published_date"
                )),
                rank,
                resultMetadata(root, item)
            ));
            fallbackRank++;
        }
        return SearchBatch.results(properties.getName(), results, searchedAt);
    }

    private SearchBatch failedForStatus(int status, Instant searchedAt) {
        String code = switch (status) {
            case 401, 403 -> "AUTHENTICATION_FAILED";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "UPSTREAM_5XX" : "UPSTREAM_HTTP_ERROR";
        };
        return SearchBatch.failed(
            properties.getName(),
            code,
            "Search provider returned HTTP " + status,
            searchedAt
        );
    }

    private void awaitRateLimit() throws InterruptedException {
        long interval = properties.getMinimumIntervalMs();
        if (interval == 0) {
            return;
        }
        while (true) {
            long now = System.currentTimeMillis();
            long current = nextRequestAt.get();
            long permitAt = Math.max(now, current);
            if (nextRequestAt.compareAndSet(current, permitAt + interval)) {
                long wait = permitAt - now;
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                return;
            }
        }
    }

    private boolean awaitRetryBackoff(int completedAttemptNumber) {
        long baseMillis = properties.getRetryBackoff().toMillis();
        if (baseMillis == 0) {
            return true;
        }
        try {
            Thread.sleep(baseMillis * completedAttemptNumber);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean acquireCircuitPermission() {
        while (true) {
            long state = circuitOpenUntil.get();
            if (state == 0) {
                return true;
            }
            if (state == -1) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (now < state) {
                return false;
            }
            if (circuitOpenUntil.compareAndSet(state, -1)) {
                return true;
            }
        }
    }

    private void closeCircuit() {
        consecutiveFailures.set(0);
        circuitOpenUntil.set(0);
    }

    private void releaseHalfOpenProbe() {
        circuitOpenUntil.compareAndSet(-1, 0);
    }

    private void recordCircuitFailure() {
        boolean halfOpen = circuitOpenUntil.get() == -1;
        int failures = consecutiveFailures.incrementAndGet();
        if (
            halfOpen
                || failures
                    >= properties.getCircuitBreakerFailureThreshold()
        ) {
            circuitOpenUntil.set(
                System.currentTimeMillis()
                    + properties.getCircuitBreakerOpenDuration().toMillis()
            );
            meterRegistry.counter(
                "atlas.search.circuit.opened",
                "provider",
                properties.getName()
            ).increment();
        } else {
            releaseHalfOpenProbe();
        }
    }

    private double circuitState() {
        long state = circuitOpenUntil.get();
        return state == 0 ? 0.0D : 1.0D;
    }

    private void recordUpstreamCall(SearchBatch batch) {
        meterRegistry.counter(
            "atlas.search.upstream.calls",
            "provider",
            properties.getName(),
            "outcome",
            isSuccess(batch) ? "success" : "failure"
        ).increment();
    }

    private void recordCompletion(SearchBatch batch, long startedAt) {
        String outcome = isSuccess(batch)
            ? "success"
            : failureTag(batch.failureCode());
        meterRegistry.counter(
            "atlas.search.requests",
            "provider",
            properties.getName(),
            "outcome",
            outcome
        ).increment();
        Timer.builder("atlas.search.duration")
            .description("End-to-end search provider duration")
            .tag("provider", properties.getName())
            .tag("outcome", outcome)
            .register(meterRegistry)
            .record(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
        DistributionSummary.builder("atlas.search.results")
            .description("Number of search results returned")
            .tag("provider", properties.getName())
            .register(meterRegistry)
            .record(batch.results().size());
    }

    private static String failureTag(String code) {
        return code == null
            ? "failure"
            : code.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isSuccess(SearchBatch batch) {
        return batch.status() != SearchBatchStatus.FAILED;
    }

    private SearchBatch interrupted(Instant searchedAt) {
        return SearchBatch.failed(
            properties.getName(),
            "SEARCH_INTERRUPTED",
            "Search request was interrupted",
            searchedAt
        );
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstText(
        JsonNode node,
        String primary,
        String fallback
    ) {
        String value = text(node, primary);
        return value == null || value.isBlank()
            ? text(node, fallback)
            : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Map<String, String> resultMetadata(JsonNode root, JsonNode item) {
        Map<String, String> values = new LinkedHashMap<>(
            metadata(item.path("metadata"))
        );
        putText(values, "request_id", text(root, "request_id"));
        putText(values, "response_time", text(root, "response_time"));
        putText(values, "score", text(item, "score"));
        putText(values, "favicon", text(item, "favicon"));
        if (isTavily()) {
            values.put("source_provider", "tavily");
            String rawContent = text(item, "raw_content");
            if (rawContent != null) {
                values.put("content_depth", "FULL_TEXT");
                values.put(
                    "raw_content_truncated",
                    Boolean.toString(rawContent.length() > MAX_RAW_CONTENT_LENGTH)
                );
            }
        }
        return values;
    }

    private static void putText(
        Map<String, String> metadata,
        String key,
        String value
    ) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private boolean isTavily() {
        return properties.getProviderType()
            == SearchProviderProperties.Provider.ProviderType.TAVILY;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException first) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static Map<String, String> metadata(JsonNode value) {
        if (!value.isObject()) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            metadata.put(field.getKey(), field.getValue().asText());
        }
        return metadata;
    }

    private record SearchAttempt(
        SearchBatch batch,
        boolean retryable,
        String retryReason
    ) {
    }
}
