package com.atlas.enterprise;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class AgentConversationApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void persistsConversationAndInheritsMultiTurnContext() throws Exception {
        String createdBody = mockMvc.perform(
                post("/api/agent/conversations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Operator-Id", "conversation-operator")
                    .content("""
                        {"title":"童程童慧风险排查"}
                        """)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.conversation_id", notNullValue()))
            .andExpect(jsonPath(
                "$.title",
                is("童程童慧风险排查")
            ))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String conversationId = objectMapper.readTree(createdBody)
            .path("conversation_id")
            .asText();

        mockMvc.perform(
                post(
                    "/api/agent/conversations/{conversationId}/messages",
                    conversationId
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "conversation-first")
                    .header("X-Operator-Id", "conversation-operator")
                    .content(objectMapper.writeValueAsString(Map.of(
                        "message",
                        "更新JSON样本企业有限公司的风险报告"
                    )))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.conversation_id",
                is(conversationId)
            ))
            .andExpect(jsonPath("$.type", is("TASK_CREATED")))
            .andExpect(jsonPath("$.required_inputs.length()", is(0)));

        String taskBody = mockMvc.perform(
                post(
                    "/api/agent/conversations/{conversationId}/messages",
                    conversationId
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "conversation-second")
                    .header("X-Operator-Id", "conversation-operator")
                    .content(objectMapper.writeValueAsString(Map.of(
                        "message",
                        "查询当前任务进度"
                    )))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("TASK_STATUS")))
            .andExpect(jsonPath(
                "$.workspace.task.status",
                is("CALCULATING_RISK")
            ))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode taskResponse = objectMapper.readTree(taskBody);
        String taskId = taskResponse.path("workspace")
            .path("task")
            .path("task_id")
            .asText();
        String assistantMessageId = taskResponse.path("message_id").asText();

        mockMvc.perform(
                post(
                    "/api/agent/conversations/{conversationId}/messages",
                    conversationId
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "conversation-second")
                    .header("X-Operator-Id", "conversation-operator")
                    .content(objectMapper.writeValueAsString(Map.of(
                        "message",
                        "原报告已经选择，继续",
                        "previous_report_file_id",
                        "report-v1"
                    )))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message_id", is(assistantMessageId)))
            .andExpect(jsonPath(
                "$.workspace.task.task_id",
                is(taskId)
            ));

        mockMvc.perform(
                get(
                    "/api/agent/conversations/{conversationId}",
                    conversationId
                )
                    .header("X-Operator-Id", "conversation-operator")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_id", is(taskId)))
            .andExpect(jsonPath(
                "$.company_query",
                is("JSON样本企业有限公司")
            ))
            .andExpect(jsonPath("$.previous_report_file_id").doesNotExist());

        mockMvc.perform(
                get(
                    "/api/agent/conversations/{conversationId}/messages",
                    conversationId
                )
                    .header("X-Operator-Id", "conversation-operator")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(4)))
            .andExpect(jsonPath("$[0].role", is("USER")))
            .andExpect(jsonPath("$[1].role", is("ASSISTANT")))
            .andExpect(jsonPath("$[2].role", is("USER")))
            .andExpect(jsonPath("$[3].role", is("ASSISTANT")))
            .andExpect(jsonPath("$[3].task_id", is(taskId)));

        mockMvc.perform(
                get(
                    "/api/agent/conversations/{conversationId}",
                    conversationId
                )
                    .header("X-Operator-Id", "other-operator")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath(
                "$.code",
                is("AGENT_TASK_ACCESS_DENIED")
            ));
    }

    @Test
    void archivesConversationWithoutDeletingItsTaskHistory() throws Exception {
        String body = mockMvc.perform(post("/api/agent/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "archive-operator")
                .content("{\"title\":\"待移除对话\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String conversationId = objectMapper.readTree(body).path("conversation_id").asText();

        mockMvc.perform(delete("/api/agent/conversations/{conversationId}", conversationId)
                .header("X-Operator-Id", "other-operator"))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/agent/conversations/{conversationId}", conversationId)
                .header("X-Operator-Id", "archive-operator"))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/agent/conversations")
                .header("X-Operator-Id", "archive-operator"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void allowsBrowserPreflightForConversationArchive() throws Exception {
        mockMvc.perform(options("/api/agent/conversations/{conversationId}", "00000000-0000-0000-0000-000000000001")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "X-Operator-Id"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
            .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }
}
