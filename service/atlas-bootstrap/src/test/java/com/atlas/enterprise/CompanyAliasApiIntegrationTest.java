package com.atlas.enterprise;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyAliasApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void operatorAliasIsPersistedAndTriggersSupplementalSearchPlan() throws Exception {
        String taskId = createTask();
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("CALCULATING_RISK")));

        mockMvc.perform(post("/api/tasks/{taskId}/company-aliases", taskId)
                .header("X-Operator-Id", "operator-alias-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "alias_name": "样本云旗舰店",
                      "alias_type": "STORE",
                      "relation": "OPERATED_STORE",
                      "source_evidence": "运营人员已核对企业官网门店页"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.alias_name", is("样本云旗舰店")))
            .andExpect(jsonPath("$.verification_status", is("CONFIRMED")))
            .andExpect(jsonPath("$.created_by", is("operator-alias-test")));

        mockMvc.perform(get("/api/tasks/{taskId}/company-aliases", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(4)))
            .andExpect(jsonPath(
                "$[?(@.alias_name == '样本云旗舰店')]",
                hasSize(1)
            ));

        String rerun = mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/search",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.searches", hasSize(8)))
            .andExpect(jsonPath("$.evidence", hasSize(3)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode body = objectMapper.readTree(rerun);
        assertTrue(body.path("searches").findValuesAsText("query").stream()
            .anyMatch(query -> query.contains("样本云旗舰店")));
    }

    private String createTask() throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "company-alias-api-flow")
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "核验企业品牌和门店风险并更新报告",
                    "company_query", "91110101JSON000001",
                    "previous_report_file_id", "report-v1"
                ))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("task_id").asText();
    }
}
