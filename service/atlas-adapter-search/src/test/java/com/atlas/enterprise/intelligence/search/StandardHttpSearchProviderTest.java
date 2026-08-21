package com.atlas.enterprise.intelligence.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchBatchStatus;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.risk.RiskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandardHttpSearchProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsConfiguredRequestAndParsesStandardResponse() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                {
                  "results": [
                    {
                      "id": "result-1",
                      "title": "目标企业闭店信息",
                      "url": "https://news.example.test/closure",
                      "snippet": "目标企业门店已关闭，待人工核验。",
                      "published_at": "2026-07-29T01:02:03Z",
                      "rank": 1,
                      "metadata": {"channel": "news"}
                    }
                  ]
                }
                """);
        });
        server.start();

        StandardHttpSearchProvider provider = new StandardHttpSearchProvider(
            properties(server.getAddress().getPort()),
            new ObjectMapper(),
            new SimpleMeterRegistry()
        );
        SearchBatch batch = provider.search(request());

        assertEquals(SearchBatchStatus.SUCCESS_WITH_RESULTS, batch.status());
        assertEquals(1, batch.results().size());
        assertEquals("目标企业闭店信息", batch.results().getFirst().title());
        assertEquals("news", batch.results().getFirst().metadata().get("channel"));
        assertTrue(rawQuery.get().contains("q="));
        assertTrue(rawQuery.get().contains("count=5"));
        assertEquals("Bearer contract-secret", authorization.get());
    }

    @Test
    void sendsTavilyPostRequestAndMapsCitedResults() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
            ));
            authorization.set(
                exchange.getRequestHeaders().getFirst("Authorization")
            );
            respond(exchange, 200, """
                {
                  "query": "目标企业有限公司 闭店 关店",
                  "results": [
                    {
                      "title": "目标企业门店关闭情况",
                      "url": "https://news.example.test/tavily-closure",
                      "content": "公开页面显示该企业门店已经关闭，需运营核验。",
                      "raw_content": "目标企业有限公司的门店公告正文：该门店已经关闭，员工正在处理后续事项。",
                      "score": 0.91,
                      "published_date": "2026-07-29T01:02:03Z",
                      "favicon": "https://news.example.test/favicon.ico"
                    }
                  ],
                  "response_time": 0.42,
                  "request_id": "tavily-request-1"
                }
                """);
        });
        server.start();

        SearchBatch batch = new StandardHttpSearchProvider(
            tavilyProperties(server.getAddress().getPort()),
            new ObjectMapper(),
            new SimpleMeterRegistry()
        ).search(request());

        assertEquals(SearchBatchStatus.SUCCESS_WITH_RESULTS, batch.status());
        assertEquals("POST", method.get());
        assertEquals("Bearer tavily-secret", authorization.get());
        JsonNode payload = new ObjectMapper().readTree(requestBody.get());
        assertEquals(request().query(), payload.path("query").asText());
        assertEquals("basic", payload.path("search_depth").asText());
        assertEquals("general", payload.path("topic").asText());
        assertEquals(5, payload.path("max_results").asInt());
        assertEquals(false, payload.path("include_answer").asBoolean());
        assertEquals("text", payload.path("include_raw_content").asText());
        assertEquals("tousu.sina.com.cn", payload.path("include_domains").get(0).asText());
        assertEquals(
            "公开页面显示该企业门店已经关闭，需运营核验。",
            batch.results().getFirst().snippet()
        );
        assertEquals(
            "目标企业有限公司的门店公告正文：该门店已经关闭，员工正在处理后续事项。",
            batch.results().getFirst().rawContent()
        );
        assertEquals("FULL_TEXT", batch.results().getFirst().metadata().get("content_depth"));
        assertEquals(
            "tavily-request-1",
            batch.results().getFirst().metadata().get("request_id")
        );
        assertEquals(
            "tavily",
            batch.results().getFirst().metadata().get("source_provider")
        );
    }

    @Test
    void mapsRateLimitToExplicitFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange ->
            respond(exchange, 429, "{\"error\":\"rate limited\"}")
        );
        server.start();

        SearchBatch batch = new StandardHttpSearchProvider(
            properties(server.getAddress().getPort()),
            new ObjectMapper(),
            new SimpleMeterRegistry()
        ).search(request());

        assertEquals(SearchBatchStatus.FAILED, batch.status());
        assertEquals("RATE_LIMITED", batch.failureCode());
    }

    @Test
    void retriesTransientFailureAndRecordsMetrics() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange -> {
            if (upstreamCalls.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
                return;
            }
            respond(exchange, 200, "{\"results\":[]}");
        });
        server.start();
        SearchProviderProperties.Provider properties = properties(
            server.getAddress().getPort()
        );
        properties.setMaxAttempts(2);
        properties.setRetryBackoff(Duration.ZERO);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        SearchBatch batch = new StandardHttpSearchProvider(
            properties,
            new ObjectMapper(),
            meters
        ).search(request());

        assertTrue(batch.status() != SearchBatchStatus.FAILED);
        assertEquals(2, upstreamCalls.get());
        assertEquals(
            1.0D,
            meters.get("atlas.search.retries")
                .tag("provider", "contract-search")
                .tag("reason", "upstream_http")
                .counter()
                .count()
        );
        assertEquals(
            2.0D,
            meters.get("atlas.search.upstream.calls")
                .tag("provider", "contract-search")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum()
        );
    }

    @Test
    void opensCircuitAfterConfiguredConsecutiveFailures() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange -> {
            upstreamCalls.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"unavailable\"}");
        });
        server.start();
        SearchProviderProperties.Provider properties = properties(
            server.getAddress().getPort()
        );
        properties.setMaxAttempts(1);
        properties.setCircuitBreakerFailureThreshold(2);
        properties.setCircuitBreakerOpenDuration(Duration.ofMinutes(1));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StandardHttpSearchProvider provider = new StandardHttpSearchProvider(
            properties,
            new ObjectMapper(),
            meters
        );

        provider.search(request());
        provider.search(request());
        SearchBatch rejected = provider.search(request());

        assertEquals(2, upstreamCalls.get());
        assertEquals(SearchBatchStatus.FAILED, rejected.status());
        assertEquals("CIRCUIT_OPEN", rejected.failureCode());
        assertEquals(
            1.0D,
            meters.get("atlas.search.circuit.opened")
                .tag("provider", "contract-search")
                .counter()
                .count()
        );
    }

    private static SearchProviderProperties.Provider properties(int port) {
        SearchProviderProperties.Provider provider =
            new SearchProviderProperties.Provider();
        provider.setName("contract-search");
        provider.setEnabled(true);
        provider.setMode(ProviderCapabilities.ProviderMode.SEARCH_ENGINE);
        provider.setBaseUrl(URI.create("http://127.0.0.1:" + port));
        provider.setPath("/v1/search");
        provider.setMaxResults(5);
        provider.setMinimumIntervalMs(0);
        provider.setMaxAttempts(1);
        provider.setRetryBackoff(Duration.ZERO);
        provider.setApiKey("contract-secret");
        return provider;
    }

    private static SearchProviderProperties.Provider tavilyProperties(
        int port
    ) {
        SearchProviderProperties.Provider provider = properties(port);
        provider.setName("tavily");
        provider.setProviderType(
            SearchProviderProperties.Provider.ProviderType.TAVILY
        );
        provider.setPath("/search");
        provider.setApiKey("tavily-secret");
        provider.setSearchDepth("basic");
        provider.setTopic("general");
        return provider;
    }

    private static SearchRequest request() {
        return new SearchRequest(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            "目标企业有限公司",
            "91110101TEST000001",
            "\"目标企业有限公司\"",
            RiskType.OTHER,
            "COMPLAINT_PLATFORMS",
            java.util.List.of("tousu.sina.com.cn", "finance.cnr.cn", "xfb365.com"),
            true,
            "general",
            Instant.parse("2026-07-30T00:00:00Z")
        );
    }

    private static void respond(
        HttpExchange exchange,
        int status,
        String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type",
            "application/json; charset=UTF-8"
        );
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
