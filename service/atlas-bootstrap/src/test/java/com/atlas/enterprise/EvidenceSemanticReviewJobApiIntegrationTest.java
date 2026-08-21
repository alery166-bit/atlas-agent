package com.atlas.enterprise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewRequest;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewOutcome;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticSuggestion;
import com.atlas.enterprise.intelligence.application.ModelUsage;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EvidenceSemanticReviewJobApiIntegrationTest.FakeModelConfiguration.class)
class EvidenceSemanticReviewJobApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void startsModelReviewAutomaticallyAndAppliesOnlySafeDecisions() throws Exception {
        String taskId = createTaskWithEvidence();

        String first = mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence/model-review/jobs/latest",
                    taskId
                ))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(!objectMapper.readTree(first).path("review_job_id").asText().isBlank());

        JsonNode completed = waitForTerminal(taskId);
        assertEquals("SUCCEEDED", completed.path("status").asText());
        assertEquals(3, completed.path("total_count").asInt());
        assertEquals(3, completed.path("processed_count").asInt());
        assertEquals(3, completed.path("reviewed_count").asInt());
        assertEquals(0, completed.path("failed_count").asInt());
        assertEquals(1, completed.path("model_call_count").asInt());
        assertEquals(120, completed.path("prompt_token_count").asInt());
        assertEquals(80, completed.path("completion_token_count").asInt());
        assertEquals(200, completed.path("total_token_count").asInt());
        assertEquals(8, completed.path("model_suggested_score").decimalValue().intValue());
        assertEquals("HIGH", completed.path("model_suggested_risk_level").asText());
        assertEquals("RISK_RULES_V1", completed.path("advisory_rule_version").asText());
        assertEquals(2, completed.path("model_score_evidence_ids").size());

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.verification_status == 'CONFIRMED')]", hasSize(2)))
            .andExpect(jsonPath("$[?(@.verification_status == 'REJECTED')]", hasSize(1)))
            .andExpect(jsonPath("$[?(@.verification_status == 'UNVERIFIED')]", hasSize(0)))
            .andExpect(jsonPath("$[?(@.metadata.llm_reviewed_at)]", hasSize(3)));

        waitForTaskStatus(taskId, "COMPLETED");
        mockMvc.perform(get("/api/tasks/{taskId}/reports", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.status == 'GENERATED')]", hasSize(1)));
    }

    @Test
    void cancelsWithoutCompletingTheTask() throws Exception {
        String taskId = createTaskWithEvidence();
        String started = mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence/model-review/jobs/latest",
                    taskId
                ))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String reviewJobId = objectMapper.readTree(started).path("review_job_id").asText();

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/evidence/model-review/jobs/{reviewJobId}/cancel",
                    taskId,
                    reviewJobId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancel_requested").value(true));

        JsonNode cancelled = waitForTerminal(taskId);
        assertEquals("CANCELLED", cancelled.path("status").asText());
        String evidenceBody = mockMvc.perform(get(
                "/api/tasks/{taskId}/public-intelligence/evidence",
                taskId
            ))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long unverified = java.util.stream.StreamSupport.stream(
            objectMapper.readTree(evidenceBody).spliterator(), false
        ).filter(item -> "UNVERIFIED".equals(
            item.path("verification_status").asText()
        )).count();
        assertTrue(unverified > 0, "cancelled review must not complete the task");
        mockMvc.perform(get("/api/tasks/{taskId}", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CALCULATING_RISK"));
    }

    private String createTaskWithEvidence() throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "semantic-review-job-" + UUID.randomUUID())
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "核验企业风险并更新报告",
                    "company_query", "91110101JSON000001",
                    "previous_report_file_id", "report-v1"
                ))))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        String taskId = objectMapper.readTree(response).path("task_id").asText();
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk());
        mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));
        return taskId;
    }

    private JsonNode waitForTerminal(String taskId) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            String body = mockMvc.perform(get(
                        "/api/tasks/{taskId}/public-intelligence/evidence/model-review/jobs/latest",
                        taskId
                    ))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            JsonNode job = objectMapper.readTree(body);
            if (List.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED")
                .contains(job.path("status").asText())) {
                return job;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Semantic review job did not reach a terminal status");
    }

    private void waitForTaskStatus(String taskId, String expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String body = mockMvc.perform(get("/api/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            if (expected.equals(objectMapper.readTree(body).path("status").asText())) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Task did not reach status " + expected);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfiguration {
        @Bean
        @Order(0)
        EvidenceSemanticModel fakeEvidenceSemanticModel() {
            return new EvidenceSemanticModel() {
                @Override
                public boolean available(UUID taskId) {
                    return true;
                }

                @Override
                public String provider(UUID taskId) {
                    return "fixture-semantic-model";
                }

                @Override
                public String model(UUID taskId) {
                    return "fixture-v1";
                }

                @Override
                public EvidenceSemanticReviewOutcome review(
                    EvidenceSemanticReviewRequest request
                ) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Fixture model interrupted", exception);
                    }
                    List<EvidenceSemanticSuggestion> suggestions = request.evidence().stream().map(item -> {
                        boolean accessible = item.grade()
                            != com.atlas.enterprise.intelligence.EvidenceGrade.LEAD;
                        return new EvidenceSemanticSuggestion(
                            item.evidenceId(),
                            accessible
                                ? EvidenceSemanticSuggestion.Relevance.RELEVANT
                                : EvidenceSemanticSuggestion.Relevance.IRRELEVANT,
                            accessible
                                ? item.riskType()
                                : com.atlas.enterprise.risk.RiskType.OTHER,
                            0.95D,
                            accessible
                                ? "主体和风险事实明确，可自动采纳"
                                : "没有可访问引用，且内容只是模型式提示",
                            item.snippet()
                        );
                    }).toList();
                    return new EvidenceSemanticReviewOutcome(
                        suggestions,
                        new ModelUsage(1, 120, 80, 200)
                    );
                }
            };
        }
    }
}
