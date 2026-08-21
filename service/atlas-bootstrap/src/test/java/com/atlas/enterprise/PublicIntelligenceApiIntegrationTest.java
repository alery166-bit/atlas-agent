package com.atlas.enterprise;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.risk.RiskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
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
class PublicIntelligenceApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TaskEventStore taskEvents;

    @Autowired
    PublicIntelligenceRepository publicIntelligenceRepository;

    @Test
    void searchesDeduplicatesConfirmsAndCalculatesFromEvidence() throws Exception {
        String taskId = createAndExecuteTask();

        String firstSearch = mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath(
                "$[?(@.source_provider == 'fixture-search')]",
                hasSize(2)
            ))
            .andExpect(jsonPath(
                "$[?(@.source_provider == 'fixture-llm-search')]",
                hasSize(1)
            ))
            .andExpect(jsonPath("$[?(@.grade == 'LEAD')]", hasSize(1)))
            .andExpect(jsonPath(
                "$[?(@.verification_status == 'UNVERIFIED')]",
                hasSize(3)
            ))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode first = objectMapper.readTree(firstSearch);

        UUID metadataEvidenceId = UUID.fromString(first.get(0).path("evidence_id").asText());
        PublicEvidence storedEvidence = publicIntelligenceRepository
            .findEvidenceById(metadataEvidenceId)
            .orElseThrow();
        Map<String, String> updatedMetadata = new LinkedHashMap<>(storedEvidence.metadata());
        updatedMetadata.put("llm_reviewed_at", "2026-08-07T00:00:00Z");
        publicIntelligenceRepository.updateEvidence(
            storedEvidence.withMetadata(updatedMetadata).withRiskType(RiskType.WAGE_ARREARS)
        );
        assertEquals(
            "2026-08-07T00:00:00Z",
            publicIntelligenceRepository.findEvidenceById(metadataEvidenceId)
                .orElseThrow().metadata().get("llm_reviewed_at")
        );
        assertEquals(
            RiskType.WAGE_ARREARS,
            publicIntelligenceRepository.findEvidenceById(metadataEvidenceId)
                .orElseThrow().riskType()
        );
        publicIntelligenceRepository.updateEvidence(
            publicIntelligenceRepository.findEvidenceById(metadataEvidenceId)
                .orElseThrow().withRiskType(storedEvidence.riskType())
        );

        String closureEvidenceId = null;
        String leadEvidenceId = null;
        List<String> evidenceIds = new ArrayList<>();
        for (JsonNode item : first) {
            evidenceIds.add(item.path("evidence_id").asText());
            if ("STORE_CLOSURE".equals(item.path("risk_type").asText())) {
                closureEvidenceId = item.path("evidence_id").asText();
            }
            if ("LEAD".equals(item.path("grade").asText())) {
                leadEvidenceId = item.path("evidence_id").asText();
            }
        }
        if (closureEvidenceId == null) {
            throw new AssertionError("Expected fixture closure evidence");
        }
        if (leadEvidenceId == null) {
            throw new AssertionError("Expected fixture LLM lead");
        }

        String contentSnapshot = mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/content-snapshot",
                    taskId,
                    closureEvidenceId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("CAPTURED")))
            .andExpect(jsonPath("$.http_status", is(200)))
            .andExpect(jsonPath("$.truncated", is(false)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String contentSnapshotId = objectMapper.readTree(contentSnapshot)
            .path("content_snapshot_id")
            .asText();

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/content-snapshot",
                    taskId,
                    closureEvidenceId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content_snapshot_id", is(contentSnapshotId)));

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/content-snapshots",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].extracted_text").isNotEmpty());

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/search",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.searches", hasSize(4)))
            .andExpect(jsonPath("$.evidence", hasSize(3)));

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision",
                    taskId,
                    leadEvidenceId
                )
                .header("X-Operator-Id", "operator-w5-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision": "CONFIRMED",
                      "reason": "尝试确认无原文引用的大模型线索"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath(
                "$.code",
                is("INVALID_PUBLIC_INTELLIGENCE_REQUEST")
            ));

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision",
                    taskId,
                    closureEvidenceId
                )
                .header("X-Operator-Id", "operator-w5-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision": "CONFIRMED",
                      "reason": "已核对原文引用、企业全称和门店主体关系"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision", is("CONFIRMED")))
            .andExpect(jsonPath("$.operator_id", is("operator-w5-1")));

        var decisionEvents = taskEvents.findAfter(
            java.util.UUID.fromString(taskId),
            0
        ).stream().filter(event ->
            event.type().equals("public.intelligence.evidence.decided")
        ).toList();
        assertEquals(1, decisionEvents.size());
        assertEquals(
            closureEvidenceId,
            decisionEvents.get(0).payload().get("evidenceId")
        );
        assertEquals(
            "CONFIRMED",
            decisionEvents.get(0).payload().get("decision")
        );

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/confirmed-events",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].risk_type", is("STORE_CLOSURE")))
            .andExpect(jsonPath("$[0].evidence_ids[0]", is(closureEvidenceId)));

        String score = mockMvc.perform(post(
                    "/api/tasks/{taskId}/risk-score/calculate-from-confirmed-evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_floor_score", is(8)))
            .andExpect(jsonPath("$.original_score", is(8)))
            .andExpect(jsonPath("$.manual_score", is(8)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String scoreSnapshotId = objectMapper.readTree(score)
            .path("score_snapshot_id")
            .asText();

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status", is("CALCULATING_RISK")))
            .andExpect(jsonPath("$.evidence_progress.confirmed", is(1)))
            .andExpect(jsonPath("$.evidence_progress.unverified", is(2)))
            .andExpect(jsonPath("$.confirmation_state", is("PENDING")))
            .andExpect(jsonPath("$.confirmation_ready", is(false)))
            .andExpect(jsonPath("$.report_generation_ready", is(false)))
            .andExpect(jsonPath(
                "$.readiness_blockers[0]",
                is("UNVERIFIED_EVIDENCE")
            ))
            .andExpect(jsonPath("$.next_action", is("REVIEW_EVIDENCE")));

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/operator-confirmation",
                    taskId
                )
                .header("X-Operator-Id", "operator-w5-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("OPERATOR_CONFIRMATION_INVALID")));

        for (String evidenceId : evidenceIds) {
            if (evidenceId.equals(closureEvidenceId)) {
                continue;
            }
            mockMvc.perform(post(
                        "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision",
                        taskId,
                        evidenceId
                    )
                    .header("X-Operator-Id", "operator-w5-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "decision": "REJECTED",
                          "reason": "No original source suitable for formal report citation"
                        }
                        """))
                .andExpect(status().isOk());
        }

        String systemConfirmations = mockMvc.perform(get(
                    "/api/tasks/{taskId}/operator-confirmations",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].operator_id", is("atlas-agent:auto")))
            .andExpect(jsonPath("$[0].confirmed_evidence_count", is(1)))
            .andExpect(jsonPath("$[0].rejected_evidence_count", is(2)))
            .andReturn().getResponse().getContentAsString();
        String automaticConfirmationId = objectMapper.readTree(systemConfirmations)
            .path(0).path("confirmation_id").asText();

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status", is("COMPLETED")))
            .andExpect(jsonPath("$.evidence_progress.total", is(3)))
            .andExpect(jsonPath("$.evidence_progress.confirmed", is(1)))
            .andExpect(jsonPath("$.evidence_progress.rejected", is(2)))
            .andExpect(jsonPath("$.evidence_progress.unverified", is(0)))
            .andExpect(jsonPath("$.confirmation_state", is("VALID")))
            .andExpect(jsonPath(
                "$.latest_confirmation.confirmation_id",
                is(automaticConfirmationId)
            ))
            .andExpect(jsonPath("$.next_action", is("DOWNLOAD_REPORT")))
            .andExpect(jsonPath("$.reports.length()", is(1)));

        mockMvc.perform(get(
                    "/api/tasks/{taskId}/public-intelligence/evidence",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath(
                "$[?(@.verification_status == 'CONFIRMED')]",
                hasSize(1)
            ))
            .andExpect(jsonPath(
                "$[?(@.verification_status == 'REJECTED')]",
                hasSize(2)
            ));

        String generated = mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports",
                    taskId
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status", is("GENERATED")))
            .andExpect(jsonPath(
                "$[0].operator_confirmation_id",
                is(automaticConfirmationId)
            ))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String reportId = objectMapper.readTree(generated)
            .path(0).path("report_id")
            .asText();
        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status", is("COMPLETED")))
            .andExpect(jsonPath("$.confirmation_state", is("VALID")))
            .andExpect(jsonPath("$.report_generation_ready", is(false)))
            .andExpect(jsonPath("$.next_action", is("DOWNLOAD_REPORT")))
            .andExpect(jsonPath("$.reports.length()", is(1)))
            .andExpect(jsonPath("$.reports[0].report_id", is(reportId)));
        byte[] docx = mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/{reportId}/download",
                    taskId,
                    reportId
                ))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        String documentXml = documentXml(docx);
        assertTrue(documentXml.contains("纳入1条负面公开证据"));
        assertTrue(documentXml.contains("门店关闭"));
        assertTrue(documentXml.contains("样本云门店关闭信息"));
        assertTrue(documentXml.contains("相关门店已经闭店"));
        assertTrue(documentXml.contains("example.test"));
        assertTrue(documentXml.contains("舆情事项"));
        assertTrue(documentXml.contains("发布时间"));
        assertTrue(documentXml.contains("内容摘要"));
        assertTrue(documentXml.contains("查看原文"));
    }

    private String createAndExecuteTask() throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "w5-public-intelligence-flow")
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "核验企业失联、欠薪和闭店风险并更新报告",
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

    private static String documentXml(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Generated DOCX has no word/document.xml");
    }
}
