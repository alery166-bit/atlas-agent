package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "atlas.data.offline.root=classpath:fixtures/company-data-missing"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskSourceFailureIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void requiredStructuredSourceFailureStopsBeforeNextStage() throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "w2-required-source-failure")
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "更新北京来源失败测试有限公司的风险报告",
                    "company_query", "北京来源失败测试有限公司",
                    "previous_report_file_id", "report-v1"
                ))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String taskId = objectMapper.readTree(response).path("task_id").asText();

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("SOURCE_FAILED")))
            .andExpect(jsonPath("$.failed_step", is("COLLECT_STRUCTURED_DATA")))
            .andExpect(jsonPath("$.error_code", is("STRUCTURED_SOURCE_QUERY_FAILED")));

        mockMvc.perform(get("/api/tasks/{taskId}/snapshot", taskId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code", is("DATA_SNAPSHOT_NOT_FOUND")));

        mockMvc.perform(get("/api/tasks/{taskId}/steps", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[1].status", is("FAILED")));
    }
}
