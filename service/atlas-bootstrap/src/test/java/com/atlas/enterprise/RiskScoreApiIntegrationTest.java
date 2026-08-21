package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.enterprise.task.port.TaskEventStore;
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
class RiskScoreApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TaskEventStore taskEvents;

    @Test
    void calculatesIdempotentlyAndPreservesOriginalDuringManualAdjustment() throws Exception {
        String taskId = createAndExecuteTask();
        String request = """
            {
              "confirmed_events": [
                {
                  "risk_type": "WAGE_ARREARS",
                  "reference_id": "finding-wage-1",
                  "title": "Confirmed wage arrears",
                  "evidence_ids": ["evidence-wage-1"]
                },
                {
                  "risk_type": "STORE_CLOSURE",
                  "reference_id": "finding-closure-1",
                  "title": "Confirmed store closure",
                  "evidence_ids": ["evidence-closure-1"]
                }
              ]
            }
            """;

        String first = mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/calculate",
                    taskId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.legacy_score", is(3.25)))
            .andExpect(jsonPath("$.rule_calculated_score", is(3.25)))
            .andExpect(jsonPath("$.event_floor_score", is(8)))
            .andExpect(jsonPath("$.original_score", is(8)))
            .andExpect(jsonPath("$.manual_score", is(8)))
            .andExpect(jsonPath("$.original_risk_level", is("HIGH")))
            .andExpect(jsonPath("$.rule_hits.length()", is(3)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode firstJson = objectMapper.readTree(first);
        String scoreSnapshotId = firstJson.path("score_snapshot_id").asText();
        JsonNode closureHit = java.util.stream.StreamSupport.stream(
                firstJson.path("rule_hits").spliterator(), false
            )
            .filter(hit -> "EVENT_FLOOR_STORE_CLOSURE".equals(
                hit.path("rule_code").asText()
            ))
            .findFirst()
            .orElseThrow();
        assertEquals("finding-closure-1", closureHit.path("references").get(0).asText());
        assertEquals("evidence-closure-1", closureHit.path("references").get(1).asText());

        mockMvc.perform(post("/api/tasks/{taskId}/risk-score/calculate", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score_snapshot_id", is(scoreSnapshotId)));

        mockMvc.perform(get("/api/tasks/{taskId}/risk-score/history", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].revision_no", is(1)))
            .andExpect(jsonPath("$[0].trigger_type", is("SYSTEM_CALCULATION")))
            .andExpect(jsonPath("$[0].final_score", is(8.0)))
            .andExpect(jsonPath("$[0].final_labels.length()", is(2)));

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/{scoreSnapshotId}/adjustments",
                    taskId,
                    scoreSnapshotId
                )
                .header("X-Operator-Id", "operator-risk-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "manual_score": 4.5,
                      "reason_code": "RULE_LIMITATION",
                      "reason_text": "Verified context requires a lower operator score"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score.original_score", is(8)))
            .andExpect(jsonPath("$.score.manual_score", is(4.5)))
            .andExpect(jsonPath("$.score.manual_risk_level", is("MEDIUM")))
            .andExpect(jsonPath("$.floor_override_warning", is(true)))
            .andExpect(jsonPath("$.decision.reason_code", is("RULE_LIMITATION")));

        mockMvc.perform(get("/api/tasks/{taskId}/risk-score", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.original_score", is(8)))
            .andExpect(jsonPath("$.manual_score", is(4.5)));

        mockMvc.perform(get("/api/tasks/{taskId}/risk-score/decisions", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].operator_id", is("operator-risk-1")));

        mockMvc.perform(get("/api/tasks/{taskId}/risk-score/history", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(2)))
            .andExpect(jsonPath("$[0].revision_no", is(2)))
            .andExpect(jsonPath("$[0].trigger_type", is("MANUAL_SCORE_ADJUSTMENT")))
            .andExpect(jsonPath("$[0].final_score", is(4.5)))
            .andExpect(jsonPath("$[0].actor_id", is("operator-risk-1")))
            .andExpect(jsonPath("$[0].final_labels.length()", is(2)))
            .andExpect(jsonPath("$[1].final_score", is(8.0)));

        var scoreEvents = taskEvents.findAfter(
            java.util.UUID.fromString(taskId),
            0
        ).stream().filter(event -> event.type().startsWith("risk.score.")).toList();
        assertEquals(2, scoreEvents.size());
        assertEquals("risk.score.calculated", scoreEvents.get(0).type());
        assertEquals(scoreSnapshotId, scoreEvents.get(0).payload().get(
            "scoreSnapshotId"
        ));
        assertEquals("risk.score.adjusted", scoreEvents.get(1).type());
        assertEquals("4.5", scoreEvents.get(1).payload().get("manualScore"));
        assertEquals("true", scoreEvents.get(1).payload().get(
            "floorOverrideWarning"
        ));
    }

    @Test
    void rejectsShortOtherReason() throws Exception {
        String taskId = createAndExecuteTask("w3-score-other-validation");
        String score = mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/calculate",
                    taskId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String scoreSnapshotId = objectMapper.readTree(score)
            .path("score_snapshot_id")
            .asText();

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/{scoreSnapshotId}/adjustments",
                    taskId,
                    scoreSnapshotId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "manual_score": 5,
                      "reason_code": "OTHER",
                      "reason_text": "short"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_RISK_SCORE_REQUEST")));
    }

    private String createAndExecuteTask() throws Exception {
        return createAndExecuteTask("w3-score-flow");
    }

    private String createAndExecuteTask(String idempotencyKey) throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "Update the enterprise risk report",
                    "company_query", "91110101JSON000001",
                    "previous_report_file_id", "report-v1"
                ))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String taskId = objectMapper.readTree(response).path("task_id").asText();
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("CALCULATING_RISK")));
        return taskId;
    }
}
