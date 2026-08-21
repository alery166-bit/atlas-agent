package com.atlas.enterprise.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.configuration.application.ConnectorConfigurationCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatClientTest {
    @Test
    void retriesOnceWithJsonRepairInstructionWhenContentIsInvalid()
        throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> repairedRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            int call = calls.incrementAndGet();
            String content = call == 1
                ? "结论如下：不是JSON"
                : "{\"schema_version\":\"agent-intent.v1\",\"intent\":\"UNKNOWN\",\"confidence\":0.5}";
            if (call == 2) repairedRequest.set(request);
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "choices", java.util.List.of(Map.of(
                    "message", Map.of("role", "assistant", "content", content)
                ))
            ));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ConnectorConfigurationCodec codec = new ConnectorConfigurationCodec(objectMapper);
            String document = """
                {"schema_version":"atlas-connector.v1","category":"MODEL","kind":"OPENAI_COMPATIBLE_LLM","enabled":true,"required":false,"failure_policy":"OPTIONAL","credential_ref":"env:ATLAS_LLM_API_KEY","endpoint":{"base_url":"http://127.0.0.1:%d","path":"/chat/completions","connect_timeout_ms":1000,"request_timeout_ms":3000},"retry":{"max_attempts":1,"backoff_ms":0},"settings":{"provider":"TEST","model":"json-model","temperature":0.1,"max_tokens":128,"intent_enabled":true,"evidence_review_enabled":true,"prompt_template":"只使用输入证据。","citation_required":true,"citation_threshold":0.8}}
                """.formatted(server.getAddress().getPort()).trim();
            var model = new PublishedModelConnectorResolver.ResolvedModel(
                "model.test.json", 1, "checksum", codec.parse(document), "secret-key"
            );

            JsonNode response = new OpenAiCompatibleChatClient(objectMapper, meters).complete(
                model,
                "Return JSON only.",
                Map.of("message", "repair")
            );

            assertEquals(2, calls.get());
            assertEquals("UNKNOWN", response.path("intent").asText());
            assertTrue(repairedRequest.get().path("messages").get(0)
                .path("content").asText().contains("上一次输出不是合法JSON"));
            assertEquals(1D, meters.get("atlas.model.invalid_json")
                .tag("model", "json-model")
                .tag("recovered", "true")
                .counter().count());
        } finally {
            server.stop(0);
            meters.close();
        }
    }

    @Test
    void callsCompatibleChatCompletionsRetriesAndParsesJsonOnlyContent()
        throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                byte[] body = "{\"error\":{\"message\":\"retry\"}}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            String content = "```json\n{\"schema_version\":\"agent-intent.v1\","
                + "\"intent\":\"CREATE_RISK_REPORT_TASK\",\"confidence\":0.96}\n```";
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "choices", java.util.List.of(Map.of(
                    "message", Map.of("role", "assistant", "content", content)
                )),
                "usage", Map.of(
                    "prompt_tokens", 100,
                    "completion_tokens", 40,
                    "total_tokens", 140
                )
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ConnectorConfigurationCodec codec = new ConnectorConfigurationCodec(objectMapper);
            String document = """
                {"schema_version":"atlas-connector.v1","category":"MODEL","kind":"OPENAI_COMPATIBLE_LLM","enabled":true,"required":false,"failure_policy":"OPTIONAL","credential_ref":"env:ATLAS_LLM_API_KEY","endpoint":{"base_url":"http://127.0.0.1:%d/compatible-mode/v1","path":"/chat/completions","connect_timeout_ms":1000,"request_timeout_ms":3000},"retry":{"max_attempts":2,"backoff_ms":0},"settings":{"provider":"ALIYUN_BAILIAN","model":"qwen3.8-max","temperature":0.1,"max_tokens":4096,"intent_enabled":true,"evidence_review_enabled":true,"prompt_template":"只使用输入证据。","citation_required":true,"citation_threshold":0.8}}
                """.formatted(server.getAddress().getPort()).trim();
            var model = new PublishedModelConnectorResolver.ResolvedModel(
                "model.bailian.primary", 1, "checksum", codec.parse(document), "secret-key"
            );

            JsonNode response = new OpenAiCompatibleChatClient(objectMapper, meters).complete(
                model,
                "Return JSON only.",
                Map.of("message", "生成测试企业风险报告")
            );

            assertEquals(2, calls.get());
            assertEquals("Bearer secret-key", authorization.get());
            assertEquals("qwen3.8-max", requestBody.get().path("model").asText());
            assertEquals(4096, requestBody.get().path("max_tokens").asInt());
            assertEquals("agent-intent.v1", response.path("schema_version").asText());
            assertEquals("CREATE_RISK_REPORT_TASK", response.path("intent").asText());
            assertTrue(meters.find("atlas.model.calls").counters().size() >= 2);
            assertEquals(
                140D,
                meters.get("atlas.model.tokens")
                    .tag("model", "qwen3.8-max")
                    .tag("type", "total")
                    .counter()
                    .count()
            );
        } finally {
            server.stop(0);
            meters.close();
        }
    }

    @Test
    void recordsRequestTimeouts() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(1_500L);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
                // The client is expected to close the timed-out exchange.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ConnectorConfigurationCodec codec = new ConnectorConfigurationCodec(objectMapper);
            String document = """
                {"schema_version":"atlas-connector.v1","category":"MODEL","kind":"OPENAI_COMPATIBLE_LLM","enabled":true,"required":false,"failure_policy":"OPTIONAL","credential_ref":"env:ATLAS_LLM_API_KEY","endpoint":{"base_url":"http://127.0.0.1:%d","path":"/chat/completions","connect_timeout_ms":1000,"request_timeout_ms":1000},"retry":{"max_attempts":1,"backoff_ms":0},"settings":{"provider":"TEST","model":"slow-model","temperature":0.1,"max_tokens":128,"intent_enabled":true,"evidence_review_enabled":true,"prompt_template":"只使用输入证据。","citation_required":true,"citation_threshold":0.8}}
                """.formatted(server.getAddress().getPort()).trim();
            var model = new PublishedModelConnectorResolver.ResolvedModel(
                "model.test.slow", 1, "checksum", codec.parse(document), "secret-key"
            );

            assertThrows(
                RuntimeException.class,
                () -> new OpenAiCompatibleChatClient(objectMapper, meters).complete(
                    model,
                    "Return JSON only.",
                    Map.of("message", "timeout")
                )
            );

            assertEquals(
                1D,
                meters.get("atlas.model.calls")
                    .tag("model", "slow-model")
                    .tag("outcome", "timeout")
                    .counter()
                    .count()
            );
        } finally {
            server.stop(0);
            meters.close();
        }
    }
}
