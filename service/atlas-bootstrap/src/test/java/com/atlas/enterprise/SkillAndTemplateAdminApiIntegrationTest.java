package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.enterprise.report.port.ReportDocumentSource;
import com.atlas.enterprise.configuration.application.SkillConfigurationCodec;
import com.atlas.enterprise.configuration.application.ReportTemplateConfigurationValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkillAndTemplateAdminApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ReportDocumentSource documents;
    @Autowired SkillConfigurationCodec skillCodec;
    @Autowired ReportTemplateConfigurationValidator templateValidator;

    @Test
    void rejectsSkillFieldsThatTheExecutorDoesNotConsume() throws Exception {
        JsonNode skills = json(postOk(
            "/api/platform/skills/initialize",
            Map.of("environment", "DEV", "operator_id", "skill-truth-test")
        ));
        String value = findConfiguration(skills, "skill.company.resolve")
            .path("versions").get(0).path("value_json").asText();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> skillCodec.parse(value.replace("\"failure_policy\":\"STOP\"", "\"failure_policy\":\"OPTIONAL\""))
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> skillCodec.parse(value.replace("\"minimum_confidence\":0.8", "\"minimum_confidence\":0.6"))
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> skillCodec.parse(value.replace("\"company.query\"", "\"company.fake_input\""))
        );
    }

    @Test
    void rejectsTemplateLocatorThatDoesNotExistInDocx() throws Exception {
        JsonNode initialized = json(postOk(
            "/api/platform/report-templates/initialize",
            Map.of("environment", "DEV", "operator_id", "template-truth-test")
        ));
        JsonNode version = initialized.path("configuration").path("versions").get(0);
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
            version.path("value_json").asText()
        );
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("field_mapping"))
            .put("risk_score", "不存在的风险分定位词");
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> templateValidator.validate(objectMapper.writeValueAsString(value))
        );
    }

    @Test
    void initializesSkillsRejectsMissingDependenciesAndGatesFrozenTasks() throws Exception {
        JsonNode skills = json(postOk(
            "/api/platform/skills/initialize",
            Map.of("environment", "DEV", "operator_id", "skill-admin")
        ));
        org.junit.jupiter.api.Assertions.assertEquals(5, skills.size());

        JsonNode resolution = findConfiguration(skills, "skill.company.resolve");
        JsonNode version = resolution.path("versions").get(0);
        JsonNode disabled = objectMapper.readTree(version.path("value_json").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) disabled).put("enabled", false);
        JsonNode updated = json(putOk(
            "/api/platform/configurations/versions/" + version.path("version_id").asText(),
            Map.of(
                "expected_row_version", 0,
                "value_json", objectMapper.writeValueAsString(disabled),
                "operator_id", "skill-admin"
            )
        ));
        validateVersion(version.path("version_id").asText(), updated.path("row_version").asLong());
        publishVersion(version.path("version_id").asText(), "publish-disabled-resolution");

        String taskId = createTask("disabled-skill-task");
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId)
                .header("X-Worker-Id", "skill-test-worker"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("SKILL_DISABLED")));

        JsonNode search = findConfiguration(skills, "skill.intelligence.search");
        JsonNode searchVersion = search.path("versions").get(0);
        JsonNode searchDocument = objectMapper.readTree(searchVersion.path("value_json").asText());
        var dependency = objectMapper.createObjectNode();
        dependency.put("config_key", "search.required-but-missing");
        dependency.put("category", "SEARCH");
        dependency.put("required", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) searchDocument)
            .putArray("dependencies").add(dependency);
        JsonNode changed = json(putOk(
            "/api/platform/configurations/versions/" + searchVersion.path("version_id").asText(),
            Map.of(
                "expected_row_version", 0,
                "value_json", objectMapper.writeValueAsString(searchDocument),
                "operator_id", "skill-admin"
            )
        ));
        validateVersion(searchVersion.path("version_id").asText(), changed.path("row_version").asLong());
        mockMvc.perform(post("/api/platform/configurations/versions/{id}/publish",
                searchVersion.path("version_id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "environment", "DEV", "idempotency_key", "missing-skill-dependency",
                    "operator_id", "skill-admin"
                ))))
            .andExpect(status().isConflict());
    }

    @Test
    void initializesInspectsPublishesAndRejectsInvalidDocxTemplates() throws Exception {
        JsonNode initialized = json(postOk(
            "/api/platform/report-templates/initialize",
            Map.of("environment", "DEV", "operator_id", "template-admin")
        ));
        JsonNode version = initialized.path("configuration").path("versions").get(0);
        String versionId = version.path("version_id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/platform/report-templates/versions/{id}/preview", versionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inspection.valid", is(true)))
            .andExpect(jsonPath("$.field_mapping.company_name", is("企业名称")));
        validateVersion(versionId, 0);
        publishVersion(versionId, "publish-formal-template");
        String templateTaskId = createTask("managed-template-task");
        org.junit.jupiter.api.Assertions.assertEquals(
            "V1-370050ab5e81",
            documents.loadTemplate(java.util.UUID.fromString(templateTaskId)).templateVersion()
        );

        MockMultipartFile invalid = new MockMultipartFile(
            "file", "invalid.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE,
            "not-a-docx".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/platform/report-templates/uploads")
                .file(invalid)
                .param("operatorId", "template-admin"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("REPORT_GENERATION_INVALID")));

        MockMultipartFile valid = new MockMultipartFile(
            "file", "formal-v2.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            documents.loadTemplate().content()
        );
        JsonNode uploaded = json(mockMvc.perform(multipart(
                "/api/platform/report-templates/uploads")
                .file(valid)
                .param("operatorId", "template-admin")
                .param("fieldMappingJson", "{\"company_name\":\"企业名称\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        JsonNode draft = uploaded.path("configuration").path("versions").get(0);
        mockMvc.perform(post("/api/platform/configurations/versions/{id}/validate",
                draft.path("version_id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "expected_row_version", draft.path("row_version").asLong(),
                    "operator_id", "template-admin"
                ))))
            .andExpect(status().isBadRequest());
    }

    private JsonNode findConfiguration(JsonNode values, String key) {
        for (JsonNode value : values) {
            if (key.equals(value.path("definition").path("config_key").asText())) return value;
        }
        throw new AssertionError("Configuration not found: " + key);
    }

    private void validateVersion(String versionId, long rowVersion) throws Exception {
        postOk(
            "/api/platform/configurations/versions/" + versionId + "/validate",
            Map.of("expected_row_version", rowVersion, "operator_id", "platform-admin")
        );
    }

    private void publishVersion(String versionId, String idempotencyKey) throws Exception {
        postOk(
            "/api/platform/configurations/versions/" + versionId + "/publish",
            Map.of(
                "environment", "DEV", "idempotency_key", idempotencyKey,
                "operator_id", "platform-admin"
            )
        );
    }

    private String createTask(String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Operator-Id", "operator-1")
                .content("""
                    {"prompt":"更新测试企业风险报告","company_query":"测试企业有限公司","previous_report_file_id":"report-v1"}
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
