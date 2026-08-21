package com.atlas.enterprise;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ReportApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void generatesVersionsDiffsAndDownloadsTheFormalDocxIdempotently() throws Exception {
        String taskId = createAndExecuteTask(
            "w4-report-flow",
            "operator-report-list"
        );
        String score = mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/calculate",
                    taskId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "confirmed_events": [{
                        "risk_type": "STORE_CLOSURE",
                        "reference_id": "closure-1",
                        "title": "Confirmed store closure",
                        "evidence_ids": ["evidence-closure-1"]
                      }]
                    }
                    """))
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
                .header("X-Operator-Id", "operator-report-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "manual_score": 4.5,
                      "reason_code": "RULE_LIMITATION",
                      "reason_text": "运营核验后保留闭店事实并调整人工分"
                    }
                    """))
            .andExpect(status().isOk());
        String evidence = mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        for (JsonNode item : objectMapper.readTree(evidence)) {
            mockMvc.perform(post(
                        "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision",
                        taskId,
                        item.path("evidence_id").asText()
                    )
                    .header("X-Operator-Id", "operator-report-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "decision": "REJECTED",
                          "reason": "Not used in this deterministic report fixture"
                        }
                        """))
                .andExpect(status().isOk());
        }
        String confirmation = mockMvc.perform(get(
                    "/api/tasks/{taskId}/operator-confirmations",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].operator_id", is("atlas-agent:auto")))
            .andExpect(jsonPath("$[0].confirmed_evidence_count", is(0)))
            .andExpect(jsonPath("$[0].rejected_evidence_count", is(3)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String confirmationId = objectMapper.readTree(confirmation)
            .path(0).path("confirmation_id")
            .asText();

        String generated = mockMvc.perform(get("/api/tasks/{taskId}/reports", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status", is("GENERATED")))
            .andExpect(jsonPath("$[0].report_version_no", is(1)))
            .andExpect(jsonPath("$[0].file_size", greaterThan(1000)))
            .andExpect(jsonPath("$[0].parsed_previous_report").doesNotExist())
            .andExpect(jsonPath("$[0].diff.original_risk_score", is("6")))
            .andExpect(jsonPath("$[0].diff.manual_risk_score", is("6")))
            .andExpect(jsonPath("$[0].operator_confirmation_id", is(confirmationId)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode report = objectMapper.readTree(generated).path(0);
        String reportId = report.path("report_id").asText();
        String contentHash = report.path("content_hash").asText();

        mockMvc.perform(post("/api/tasks/{taskId}/reports", taskId)
                .header("X-Operator-Id", "operator-report-1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.report_id", is(reportId)))
            .andExpect(jsonPath("$.report_version_no", is(1)));

        mockMvc.perform(get("/api/tasks/{taskId}/reports", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].report_id", is(reportId)));

        mockMvc.perform(get("/api/tasks")
                .param("operator_id", "operator-report-list")
                .param("status", "COMPLETED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()", is(1)))
            .andExpect(jsonPath("$.items[0].task.task_id", is(taskId)))
            .andExpect(jsonPath("$.items[0].risk.original_score", is(6)))
            .andExpect(jsonPath("$.items[0].risk.manual_score", is(6)))
            .andExpect(jsonPath(
                "$.items[0].evidence_progress.rejected",
                is(3)
            ))
            .andExpect(jsonPath(
                "$.items[0].confirmation_state",
                is("VALID")
            ))
            .andExpect(jsonPath(
                "$.items[0].latest_confirmation.confirmation_id",
                is(confirmationId)
            ))
            .andExpect(jsonPath(
                "$.items[0].latest_report.report_id",
                is(reportId)
            ))
            .andExpect(jsonPath(
                "$.items[0].next_action",
                is("DOWNLOAD_REPORT")
            ));

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/{reportId}/diff",
                    taskId,
                    reportId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.manual_risk_score", is("6")));

        byte[] docx = mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/{reportId}/download",
                    taskId,
                    reportId
                ))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-SHA256", contentHash))
            .andExpect(header().string(
                "Content-Type",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertTrue(docx.length > 1000);
        assertTrue(docx[0] == 'P' && docx[1] == 'K');

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/latest/download",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-SHA256", contentHash));

        mockMvc.perform(post("/api/agent/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "agent-refresh-completed-report")
                .header("X-Operator-Id", "operator-report-list")
                .content(objectMapper.writeValueAsString(Map.of(
                    "message", "更新报告",
                    "task_id", taskId
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type", is("TASK_CREATED")))
            .andExpect(jsonPath(
                "$.assistant_message",
                containsString("先刷新企业信息")
            ))
            .andExpect(jsonPath(
                "$.workspace.task.task_id",
                not(taskId)
            ));
    }

    @Test
    void blocksStaleCompletedReportUntilLatestScoreIsConfirmedAndRegenerated()
        throws Exception {
        String taskId = createAndExecuteTask(
            "w4-stale-report-flow",
            "operator-stale-report"
        );
        String score = mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/calculate",
                    taskId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "confirmed_events": [{
                        "risk_type": "STORE_CLOSURE",
                        "reference_id": "stale-closure-1",
                        "title": "Stale report closure fixture",
                        "evidence_ids": ["stale-evidence-1"]
                      }]
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String scoreSnapshotId = objectMapper.readTree(score)
            .path("score_snapshot_id")
            .asText();

        String evidence = mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        for (JsonNode item : objectMapper.readTree(evidence)) {
            mockMvc.perform(post(
                        "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision",
                        taskId,
                        item.path("evidence_id").asText()
                    )
                    .header("X-Operator-Id", "operator-stale-report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "decision": "REJECTED",
                          "reason": "Prepare completed report fixture"
                        }
                        """))
                .andExpect(status().isOk());
        }

        String initialReport = mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status", is("GENERATED")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String initialReportId = objectMapper.readTree(initialReport)
            .path(0).path("report_id")
            .asText();

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/{scoreSnapshotId}/adjustments",
                    taskId,
                    scoreSnapshotId
                )
                .header("X-Operator-Id", "operator-stale-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "manual_score": 4.5,
                      "reason_code": "RULE_LIMITATION",
                      "reason_text": "验证评分变化后旧报告失效"
                    }
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status", is("COMPLETED")))
            .andExpect(jsonPath("$.confirmation_state", is("STALE")))
            .andExpect(jsonPath("$.report_generation_ready", is(false)))
            .andExpect(jsonPath("$.next_action", is("CONFIRM_REVIEW")));
        mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/latest/download",
                    taskId
                ))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/{reportId}/download",
                    taskId,
                    initialReportId
                ))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/operator-confirmation",
                    taskId
                )
                .header("X-Operator-Id", "operator-stale-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"确认最新评分\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmation_state", is("VALID")))
            .andExpect(jsonPath("$.report_generation_ready", is(true)))
            .andExpect(jsonPath("$.next_action", is("GENERATE_REPORT")));

        String regenerated = mockMvc.perform(post("/api/tasks/{taskId}/reports", taskId)
                .header("X-Operator-Id", "operator-stale-report"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.report_version_no", is(2)))
            .andReturn().getResponse().getContentAsString();
        String regeneratedReportId = objectMapper.readTree(regenerated)
            .path("report_id").asText();
        mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/{reportId}/diff",
                    taskId,
                    regeneratedReportId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previous_report_version_no", is(1)))
            .andExpect(jsonPath("$.section_changes").isArray())
            .andExpect(jsonPath("$.table_row_changes").isArray())
            .andExpect(jsonPath("$.conclusion_changes").isArray())
            .andExpect(jsonPath(
                "$.conclusion_changes[?(@.field == '风险结论 / 最终风险分')].before_value",
                hasItem("6")
            ))
            .andExpect(jsonPath(
                "$.conclusion_changes[?(@.field == '风险结论 / 最终风险分')].after_value",
                hasItem("4.5")
            ));
        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmation_state", is("VALID")))
            .andExpect(jsonPath("$.report_generation_ready", is(false)))
            .andExpect(jsonPath("$.next_action", is("DOWNLOAD_REPORT")));
        mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/latest/download",
                    taskId
                ))
            .andExpect(status().isOk());
    }

    private String createAndExecuteTask(
        String idempotencyKey,
        String operatorId
    ) throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Operator-Id", operatorId)
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
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("CALCULATING_RISK")));
        return taskId;
    }
}
