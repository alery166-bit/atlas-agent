package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.atlas.enterprise.intelligence.SearchBatchStatus;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.intelligence.port.PublicSearchProviderRegistry;
import com.atlas.enterprise.risk.RiskType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConnectorAdminApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PublicSearchProviderRegistry searchProviders;

    @Test
    void validatesDataSourceConnectionButNeverPublishesTestOnlyRecord() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"cluster_name\":\"atlas-test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JsonNode created = json(postOk(
                "/api/platform/connectors/initialize",
                Map.of("type", "ELASTICSEARCH", "operator_id", "connector-admin")
            ));
            JsonNode version = created.path("versions").get(0);
            String versionId = version.path("version_id").asText();
            String value = version.path("value_json").asText()
                .replace("\"enabled\":false", "\"enabled\":true")
                .replace("http://elasticsearch-dev:9200", "http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode updated = json(putOk(
                "/api/platform/configurations/versions/" + versionId,
                Map.of("expected_row_version", 0, "value_json", value, "operator_id", "connector-admin")
            ));

            mockMvc.perform(post("/api/platform/connectors/versions/{id}/mapping-preview", versionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "sample_record", Map.of(
                            "company_name", "映射测试企业",
                            "unified_credit_code", "911100TEST",
                            "md5", "source-1"
                        )
                    ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonical_name", is("映射测试企业")))
                .andExpect(jsonPath("$.source_entity_id", is("source-1")));

            JsonNode test = json(postOk(
                "/api/platform/connectors/versions/" + versionId + "/tests",
                Map.of("operator_id", "connector-admin")
            ));
            org.junit.jupiter.api.Assertions.assertEquals("PASSED", test.path("status").asText());

            String changed = updated.path("value_json").asText().replace("\"max_records\":5000", "\"max_records\":4000");
            JsonNode changedVersion = json(putOk(
                "/api/platform/configurations/versions/" + versionId,
                Map.of("expected_row_version", 1, "value_json", changed, "operator_id", "connector-admin")
            ));
            postOk(
                "/api/platform/configurations/versions/" + versionId + "/validate",
                Map.of("expected_row_version", changedVersion.path("row_version").asLong(), "operator_id", "connector-admin")
            );
            mockMvc.perform(post("/api/platform/configurations/versions/" + versionId + "/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "environment", "DEV", "idempotency_key", "stale-es-test",
                        "operator_id", "connector-admin"
                    ))))
                .andExpect(status().isConflict());

            postOk(
                "/api/platform/connectors/versions/" + versionId + "/tests",
                Map.of("operator_id", "connector-admin")
            );
            mockMvc.perform(post("/api/platform/configurations/versions/" + versionId + "/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "environment", "DEV", "idempotency_key", "publish-tested-es",
                        "operator_id", "connector-admin"
                    ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(
                    "V1 data-source versions are connection-test records only; task execution still uses the service Elasticsearch runtime configuration"
                )));

            mockMvc.perform(get("/api/platform/connectors").param("environment", "DEV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].test_impacts[0].latest_test.status", is("PASSED")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRequiredConnectorWithoutStopPolicy() throws Exception {
        JsonNode created = json(postOk(
            "/api/platform/connectors/initialize",
            Map.of("type", "TAVILY", "operator_id", "connector-admin")
        ));
        JsonNode version = created.path("versions").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(
            version.path("value_json").asText().contains(
                "\"credential_ref\":\"env:ATLAS_SEARCH_PRIMARY_API_KEY\""
            )
        );
        String invalid = version.path("value_json").asText()
            .replace("\"failure_policy\":\"STOP\"", "\"failure_policy\":\"OPTIONAL\"");
        JsonNode updated = json(putOk(
            "/api/platform/configurations/versions/" + version.path("version_id").asText(),
            Map.of("expected_row_version", 0, "value_json", invalid, "operator_id", "connector-admin")
        ));
        mockMvc.perform(post("/api/platform/configurations/versions/"
                + version.path("version_id").asText() + "/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "expected_row_version", updated.path("row_version").asLong(),
                    "operator_id", "connector-admin"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void newTaskExecutesItsFrozenPublishedTavilyConfiguration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = """
                {"query":"测试企业 风险","results":[{"title":"测试企业风险信息","url":"https://example.test/risk","content":"与测试企业明确相关的风险信息。","score":0.9}],"request_id":"dynamic-config-test"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.setProperty("ATLAS_DYNAMIC_TAVILY_TEST_KEY", "dynamic-secret");
        try {
            String value = """
                {"schema_version":"atlas-connector.v1","category":"SEARCH","kind":"TAVILY","enabled":true,"required":true,"failure_policy":"STOP","credential_ref":"env:ATLAS_DYNAMIC_TAVILY_TEST_KEY","endpoint":{"base_url":"http://127.0.0.1:%d","path":"/search","connect_timeout_ms":1000,"request_timeout_ms":3000},"retry":{"max_attempts":1,"backoff_ms":0},"settings":{"search_depth":"basic","topic":"general","max_results":3,"rate_limit_per_minute":60,"query_templates":["{企业名} 风险"]}}
                """.formatted(server.getAddress().getPort()).trim();
            JsonNode created = json(postOk(
                "/api/platform/configurations",
                Map.of(
                    "config_key", "search.tavily.dynamic-test",
                    "category", "SEARCH",
                    "display_name", "任务冻结 Tavily",
                    "secret_config", false,
                    "value_json", value,
                    "operator_id", "connector-admin"
                )
            ));
            String versionId = created.path("versions").get(0).path("version_id").asText();
            postOk("/api/platform/connectors/versions/" + versionId + "/tests",
                Map.of("operator_id", "connector-admin"));
            postOk("/api/platform/configurations/versions/" + versionId + "/validate",
                Map.of("expected_row_version", 0, "operator_id", "connector-admin"));
            postOk("/api/platform/configurations/versions/" + versionId + "/publish",
                Map.of("environment", "DEV", "idempotency_key", "publish-dynamic-tavily",
                    "operator_id", "connector-admin"));

            UUID taskId = UUID.fromString(createTask("dynamic-tavily-task"));
            var providers = searchProviders.providers(taskId);
            org.junit.jupiter.api.Assertions.assertEquals(1, providers.size());
            org.junit.jupiter.api.Assertions.assertTrue(
                providers.getFirst().capabilities().provider().contains("dynamic-test/v1")
            );
            var batch = providers.getFirst().search(new SearchRequest(
                taskId, UUID.randomUUID(), "测试企业有限公司", null,
                "测试企业 风险", RiskType.OTHER, Instant.now()
            ));
            org.junit.jupiter.api.Assertions.assertEquals(
                SearchBatchStatus.SUCCESS_WITH_RESULTS, batch.status()
            );
        } finally {
            System.clearProperty("ATLAS_DYNAMIC_TAVILY_TEST_KEY");
            server.stop(0);
        }
    }

    private String createTask(String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Operator-Id", "operator-1")
                .content("""
                    {"prompt":"更新动态搜索配置测试企业报告","company_query":"测试企业有限公司","previous_report_file_id":"report-v1"}
                    """))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        return json(response).path("task_id").asText();
    }

    private String postOk(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String putOk(String path, Object body) throws Exception {
        return mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
