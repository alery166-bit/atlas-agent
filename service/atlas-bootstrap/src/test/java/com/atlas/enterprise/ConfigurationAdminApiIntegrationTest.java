package com.atlas.enterprise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfigurationAdminApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    @Test
    void publishesGenericPlatformVersionsButDoesNotFakeTaskConsumption() throws Exception {
        JsonNode created = json(postJson(
            "/api/platform/configurations",
            Map.of(
                "config_key", "platform.task-snapshot-test",
                "category", "PLATFORM",
                "display_name", "任务快照测试配置",
                "description", "验证通用平台配置版本冻结",
                "secret_config", false,
                "value_json", "{\"max_results\":10}",
                "operator_id", "config-admin"
            )
        ));
        String v1 = created.path("versions").get(0).path("version_id").asText();
        validate(v1, 0);
        publish(v1, "publish-platform-v1");

        String task1 = createTask("config-snapshot-task-1");
        String task1Manifest = taskSnapshot(task1).path("manifest_json").asText();
        org.junit.jupiter.api.Assertions.assertFalse(task1Manifest.contains(v1));

        JsonNode draft = json(postJson(
            "/api/platform/configurations/platform.task-snapshot-test/drafts",
            Map.of(
                "value_json", "{\"max_results\":20}",
                "operator_id", "config-admin"
            )
        ));
        String v2 = draft.path("version_id").asText();
        validate(v2, 0);
        publish(v2, "publish-platform-v2");

        String task2 = createTask("config-snapshot-task-2");
        org.junit.jupiter.api.Assertions.assertFalse(
            taskSnapshot(task2).path("manifest_json").asText().contains(v2)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            task1Manifest,
            taskSnapshot(task1).path("manifest_json").asText()
        );

        postJson(
            "/api/platform/configurations/versions/" + v1 + "/rollback",
            Map.of(
                "environment", "DEV",
                "idempotency_key", "rollback-platform-v1",
                "operator_id", "config-admin"
            )
        );
        postJson(
            "/api/platform/configurations/versions/" + v1 + "/rollback",
            Map.of(
                "environment", "DEV",
                "idempotency_key", "rollback-platform-v1",
                "operator_id", "config-admin"
            )
        );
        mockMvc.perform(get("/api/platform/configurations").param("environment", "DEV"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].binding.active_version_id", is(v1)));

        String secretResponse = postJson(
            "/api/platform/configurations",
            Map.of(
                "config_key", "platform.secret-reference",
                "category", "PLATFORM",
                "display_name", "平台密钥引用",
                "secret_config", true,
                "value_json", "{\"provider\":\"tavily\"}",
                "secret_ref", "env:TAVILY_API_KEY",
                "operator_id", "config-admin"
            )
        );
        org.junit.jupiter.api.Assertions.assertFalse(secretResponse.contains("TAVILY_API_KEY"));
        org.junit.jupiter.api.Assertions.assertTrue(secretResponse.contains("secret_configured"));
        long auditCount = jdbc.sql("""
                SELECT COUNT(*) FROM audit_event
                 WHERE action LIKE 'configuration.%'
                """)
            .query(Long.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(auditCount >= 7);
    }

    @Test
    void rejectsPlaintextSecretsAndUnvalidatedPublish() throws Exception {
        mockMvc.perform(post("/api/platform/configurations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "config_key", "model.invalid-secret",
                    "category", "MODEL",
                    "display_name", "无效密钥",
                    "secret_config", true,
                    "value_json", "{\"api_key\":\"plaintext\"}",
                    "secret_ref", "env:MODEL_KEY",
                    "operator_id", "config-admin"
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_ARGUMENT")));
    }

    private void validate(String versionId, long rowVersion) throws Exception {
        postJson(
            "/api/platform/configurations/versions/" + versionId + "/validate",
            Map.of("expected_row_version", rowVersion, "operator_id", "config-admin")
        );
    }

    private void publish(String versionId, String idempotencyKey) throws Exception {
        postJson(
            "/api/platform/configurations/versions/" + versionId + "/publish",
            Map.of(
                "environment", "DEV",
                "idempotency_key", idempotencyKey,
                "operator_id", "config-admin"
            )
        );
    }

    private String createTask(String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Operator-Id", "operator-1")
                .content("""
                    {
                      "prompt":"更新测试企业风险报告",
                      "company_query":"测试企业有限公司",
                      "previous_report_file_id":"report-v1"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        return json(response).path("task_id").asText();
    }

    private JsonNode taskSnapshot(String taskId) throws Exception {
        return json(mockMvc.perform(post(
                "/api/platform/configurations/tasks/{taskId}/snapshot", taskId
            ).param("environment", "DEV"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private String postJson(String path, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
