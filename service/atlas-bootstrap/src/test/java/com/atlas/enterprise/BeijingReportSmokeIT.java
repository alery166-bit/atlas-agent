package com.atlas.enterprise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "atlas.data.offline.root=../../data/company",
    "atlas.report.template-path=../../data/templates/北京简熹和食品有限公司_企业风险监测分析报告20260714.docx",
    "atlas.report.previous-root=../..",
    "atlas.storage.root=../../outputs/atlas-w4/storage"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BeijingReportSmokeIT {
    private static final String COMPANY = "北京简熹和食品有限公司";
    private static final String CREDIT_CODE = "91110113MAK5DEJQ0W";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void generatesTheFirstRealBeijingReport() throws Exception {
        String created = mockMvc.perform(post("/api/tasks")
                .header("Idempotency-Key", "w4-beijing-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "更新北京简熹和食品有限公司企业风险监测分析报告",
                    "company_query", CREDIT_CODE,
                    "previous_report_file_id", "report-v1"
                ))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String taskId = objectMapper.readTree(created).path("task_id").asText();

        String executed = mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertEquals(
            "SEARCHING_PUBLIC_INTELLIGENCE",
            objectMapper.readTree(executed).path("status").asText()
        );
        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
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
                    .header("X-Operator-Id", "w4-beijing-smoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "decision": "REJECTED",
                          "reason": "Smoke test excludes fixture public evidence"
                        }
                        """))
                .andExpect(status().isOk());
        }

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
        JsonNode scoreJson = objectMapper.readTree(score);
        assertEquals("0", scoreJson.path("original_score").decimalValue()
            .stripTrailingZeros().toPlainString());

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/operator-confirmation",
                    taskId
                )
                .header("X-Operator-Id", "w4-beijing-smoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"note": "Real sample smoke-test review completed"}
                    """))
            .andExpect(status().isOk());

        String generated = mockMvc.perform(post("/api/tasks/{taskId}/reports", taskId)
                .header("X-Operator-Id", "w4-beijing-smoke"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode report = objectMapper.readTree(generated);
        assertEquals("GENERATED", report.path("status").asText());
        String reportId = report.path("report_id").asText();

        byte[] docx = mockMvc.perform(get(
                    "/api/tasks/{taskId}/reports/{reportId}/download",
                    taskId,
                    reportId
                ))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertTrue(docx.length > 1000);

        Path outputRoot = Path.of("../../outputs/atlas-w4")
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(outputRoot);
        Path reportPath = outputRoot.resolve(
            "北京简熹和食品有限公司_企业风险监测分析报告20260730_W4.docx"
        );
        Files.write(reportPath, docx);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked_at", Instant.now().toString());
        result.put("task_id", taskId);
        result.put("report_id", reportId);
        result.put("company_name", COMPANY);
        result.put("credit_code", CREDIT_CODE);
        result.put("status", report.path("status").asText());
        result.put("version", report.path("report_version_no").asInt());
        result.put("template_version", report.path("template_version").asText());
        result.put("content_hash", report.path("content_hash").asText());
        result.put("file_size", docx.length);
        result.put("previous_report_confidence",
            report.path("parsed_previous_report").path("confidence").asDouble());
        result.put("company_change_count",
            report.path("diff").path("company_changes").size());
        result.put("original_score", report.path("diff").path("original_risk_score").asText());
        result.put("manual_score", report.path("diff").path("manual_risk_score").asText());
        result.put("report_path", reportPath.toString());
        Files.writeString(
            outputRoot.resolve("w4-beijing-smoke-result.json"),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)
        );
    }
}
