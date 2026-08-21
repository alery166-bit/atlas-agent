package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GoldenAcceptanceApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void prepareArtifacts() throws Exception {
        Path root = Path.of("target", "test-golden-artifacts", "cases");
        for (int index = 1; index <= 20; index++) {
            Path folder = root.resolve(id(index));
            Files.createDirectories(folder);
            Files.write(folder.resolve("previous-report.docx"), new byte[] {'P', 'K', 3, 4});
            Files.write(folder.resolve("final-report.docx"), new byte[] {'P', 'K', 3, 4});
            Files.writeString(folder.resolve("company.json"), "{}");
            Files.writeString(folder.resolve("operator-decisions.json"), "{}");
        }
    }

    @Test
    void importsDraftAndRejectsUnsafeArtifactReference() throws Exception {
        JsonNode draft = manifest(1, false);
        mockMvc.perform(post("/api/platform/golden-acceptance/suites")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "acceptance-owner")
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", "单份草稿", "manifest", draft
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("DRAFT")))
            .andExpect(jsonPath("$.case_count", is(1)))
            .andExpect(jsonPath("$.confirmed_case_count", is(0)));

        ((ObjectNode) draft.path("cases").get(0).path("artifacts"))
            .put("previous_report", "../outside.docx");
        mockMvc.perform(post("/api/platform/golden-acceptance/suites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", "不安全样本", "manifest", draft
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_ARGUMENT")));
    }

    @Test
    void acceptsTwentyConfirmedCasesAndComputesFormalQualityGate() throws Exception {
        String body = mockMvc.perform(post("/api/platform/golden-acceptance/suites")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "business-owner")
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", "正式二十份样本", "manifest", manifest(20, true)
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("READY")))
            .andExpect(jsonPath("$.case_count", is(20)))
            .andExpect(jsonPath("$.verified_artifact_case_count", is(20)))
            .andReturn().getResponse().getContentAsString();
        String suiteId = objectMapper.readTree(body).path("suite_id").asText();

        List<Map<String, Object>> cases = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("case_id", id(index));
            item.put("subject_matched", true);
            item.put("major_risk_count", 1);
            item.put("supported_major_risk_count", 1);
            item.put("score_explainable", true);
            item.put("docx_core_fields_ok", true);
            item.put("critical_defect_count", 0);
            item.put("high_defect_count", 0);
            item.put("manual_minutes", 12.5);
            item.put("notes", "业务盲测通过");
            cases.add(item);
        }
        mockMvc.perform(post(
                    "/api/platform/golden-acceptance/suites/{suiteId}/evaluations", suiteId
                ).contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "blind-reviewer")
                .content(objectMapper.writeValueAsBytes(Map.of("cases", cases))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("PASSED")))
            .andExpect(jsonPath("$.severe_subject_mismatch_count", is(0)))
            .andExpect(jsonPath("$.supported_major_risk_count", is(20)))
            .andExpect(jsonPath("$.explainable_score_count", is(20)))
            .andExpect(jsonPath("$.docx_pass_count", is(20)))
            .andExpect(jsonPath("$.average_manual_minutes", is(12.5)));

        mockMvc.perform(get("/api/platform/golden-acceptance/suites/{suiteId}", suiteId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runs[0].status", is("PASSED")))
            .andExpect(jsonPath("$.manifest.cases.length()", is(20)));
        mockMvc.perform(get("/api/platform/audit")
                .param("event_type", "golden_acceptance.evaluated"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].operator_id", is("blind-reviewer")));
    }

    private ObjectNode manifest(int count, boolean confirmed) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "atlas-acceptance.v1");
        ArrayNode cases = root.putArray("cases");
        for (int index = 1; index <= count; index++) {
            String id = id(index);
            ObjectNode sample = cases.addObject();
            sample.put("id", id).put("business_confirmed", confirmed);
            ObjectNode company = sample.putObject("company");
            company.put("canonical_name", "黄金验收企业" + index + "有限公司")
                .put("unified_credit_code", String.format("91110101GOLDEN%04d", index));
            company.putArray("identity_terms").add("黄金验收企业" + index).add("黄金品牌" + index);
            ObjectNode artifacts = sample.putObject("artifacts");
            artifacts.put("previous_report", "cases/" + id + "/previous-report.docx")
                .put("final_report", "cases/" + id + "/final-report.docx")
                .put("company_json", "cases/" + id + "/company.json")
                .put("operator_decisions", "cases/" + id + "/operator-decisions.json");
            sample.putObject("expected").put("original_score", "8")
                .put("manual_score", "8").put("risk_level", "HIGH");
            ObjectNode evidence = sample.putArray("evidence_labels").addObject();
            evidence.put("risk_type", "STORE_CLOSURE").put("title", "门店关闭")
                .put("source_url", "https://example.com/evidence/" + index)
                .put("matched_identity_term", "黄金品牌" + index)
                .put("entity_match_expected", true).put("include_in_report", true)
                .put("major_risk", true).put("published_at", "2026-01-01");
        }
        return root;
    }

    private static String id(int index) {
        return String.format("formal-case-%02d", index);
    }
}
