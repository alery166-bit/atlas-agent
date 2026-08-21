package com.atlas.enterprise;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "atlas.skills.require-published-for-new-tasks=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublishedSkillSetGuardIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void blocksUnversionedFallbackAndFreezesAllPublishedSkillsForNewTasks() throws Exception {
        createTask("skills-not-published")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("CONFIGURATION_CONFLICT")))
            .andExpect(jsonPath("$.message", containsString("Skill 均已发布且启用")));

        JsonNode skills = json(postOk(
            "/api/platform/skills/initialize",
            Map.of("environment", "DEV", "operator_id", "skill-release-test")
        ));
        for (JsonNode skill : skills) {
            JsonNode version = skill.path("versions").get(0);
            String versionId = version.path("version_id").asText();
            postOk(
                "/api/platform/configurations/versions/" + versionId + "/validate",
                Map.of("expected_row_version", 0, "operator_id", "skill-release-test")
            );
            postOk(
                "/api/platform/configurations/versions/" + versionId + "/publish",
                Map.of(
                    "environment", "DEV",
                    "idempotency_key", "publish-" + versionId,
                    "operator_id", "skill-release-test"
                )
            );
        }

        String taskJson = createTask("skills-published")
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        String taskId = json(taskJson).path("task_id").asText();
        JsonNode snapshot = json(postOk(
            "/api/platform/configurations/tasks/" + taskId + "/snapshot?environment=DEV",
            Map.of()
        ));
        JsonNode manifest = objectMapper.readTree(snapshot.path("manifest_json").asText());
        long frozenSkills = java.util.stream.StreamSupport.stream(manifest.spliterator(), false)
            .filter(item -> item.path("config_key").asText().startsWith("skill."))
            .count();
        org.junit.jupiter.api.Assertions.assertEquals(5, frozenSkills);
    }

    private org.springframework.test.web.servlet.ResultActions createTask(String key) throws Exception {
        return mockMvc.perform(post("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key)
            .header("X-Operator-Id", "operator-1")
            .content("""
                {"prompt":"生成测试企业风险报告","company_query":"测试企业有限公司"}
                """));
    }

    private String postOk(String path, Object body) throws Exception {
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
