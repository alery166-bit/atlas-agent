package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
class AgentMessageApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsAndQueriesATaskThroughControlledNaturalLanguage() throws Exception {
        String message = "调查JSON样本企业有限公司近一年的经营状况，重点核实失联、拖欠工资和门店关闭。";

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-missing-report")
                .header("X-Operator-Id", "agent-operator")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message",
                    message
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("TASK_CREATED")))
            .andExpect(jsonPath(
                "$.parsed_intent",
                is("CREATE_RISK_REPORT_TASK")
            ))
            .andExpect(jsonPath(
                "$.company_query",
                is("JSON样本企业有限公司")
            ))
            .andExpect(jsonPath("$.required_inputs.length()", is(0)))
            .andExpect(jsonPath("$.workspace.task.status", is("CALCULATING_RISK")));

        String createdBody = mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-create-task")
                .header("X-Operator-Id", "agent-operator")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message",
                    message,
                    "previous_report_file_id",
                    "report-v1"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message_id", notNullValue()))
            .andExpect(jsonPath("$.type", is("TASK_CREATED")))
            .andExpect(jsonPath(
                "$.workspace.task.status",
                is("CALCULATING_RISK")
            ))
            .andExpect(jsonPath(
                "$.workspace.next_action",
                is("REVIEW_EVIDENCE")
            ))
            .andExpect(jsonPath(
                "$.suggested_actions[0].code",
                is("REVIEW_EVIDENCE")
            ))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode created = objectMapper.readTree(createdBody);
        String taskId = created.path("workspace")
            .path("task")
            .path("task_id")
            .asText();
        String taskNo = created.path("workspace")
            .path("task")
            .path("task_no")
            .asText();

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-create-task")
                .header("X-Operator-Id", "agent-operator")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message",
                    message,
                    "previous_report_file_id",
                    "report-v1"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.workspace.task.task_id",
                is(taskId)
            ));

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-query-cross-operator")
                .header("X-Operator-Id", "different-operator")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message",
                    "这个任务进度怎么样了？",
                    "task_id",
                    taskId
                ))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath(
                "$.code",
                is("AGENT_TASK_ACCESS_DENIED")
            ));

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-query-by-id")
                .header("X-Operator-Id", "agent-operator")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message",
                    "这个任务进度怎么样了？",
                    "task_id",
                    taskId
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("TASK_STATUS")))
            .andExpect(jsonPath(
                "$.workspace.task.task_id",
                is(taskId)
            ))
            .andExpect(jsonPath(
                "$.suggested_actions[0].endpoint",
                is("/api/tasks/" + taskId + "/public-intelligence/evidence")
            ));

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-query-by-number")
                .header("X-Operator-Id", "agent-operator")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message",
                    "任务 " + taskNo + " 进度到哪了？"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("TASK_STATUS")))
            .andExpect(jsonPath(
                "$.workspace.task.task_id",
                is(taskId)
            ));
    }

    @Test
    void refusesUnsupportedScopeAndAsksForMissingTaskReference() throws Exception {
        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-unsupported")
                .content("""
                    {
                      "message": "分析北京示例有限公司的招商线索"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("UNSUPPORTED_SCOPE")))
            .andExpect(jsonPath(
                "$.parsed_intent",
                is("UNSUPPORTED_SCOPE")
            ))
            .andExpect(jsonPath("$.workspace").doesNotExist());

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-missing-reference")
                .content("""
                    {
                      "message": "帮我查一下任务进度"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("NEEDS_INPUT")))
            .andExpect(jsonPath(
                "$.required_inputs[0].code",
                is("TASK_REFERENCE")
            ));
    }
}
