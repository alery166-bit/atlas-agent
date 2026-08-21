package com.atlas.enterprise;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.atlas.enterprise.risk.RiskType;
import com.atlas.enterprise.risk.application.RiskRulePolicyResolver;
import com.atlas.enterprise.risk.application.RiskRulePolicyCodec;
import java.util.ArrayList;
import java.util.List;
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
class RiskRuleAdminApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RiskRulePolicyResolver policyResolver;
    @Autowired RiskRulePolicyCodec policyCodec;

    @Test
    void rejectsFakeThresholdAndUnimplementedRiskLabelConfiguration() {
        String defaultJson = policyCodec.defaultJson();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> policyCodec.parse(
                defaultJson.replace("\"high_min\":8", "\"high_min\":8.5"), "test"
            )
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> policyCodec.parse(
                defaultJson.replace(
                    "\"risk_labels\":[]",
                    "\"risk_labels\":[{\"legacy_label_no\":\"100001\",\"category\":\"测试\",\"evidence_requirement\":\"一条证据\",\"priority\":1,\"enabled\":true}]"
                ),
                "test"
            )
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> policyCodec.parse(
                defaultJson.replace(
                    "\"LEGACY_EQUITY_FREEZE\":0.5",
                    "\"LEGACY_EQUITY_FREEZE\":0.5,\"FAKE_UNUSED_RULE\":1"
                ),
                "test"
            )
        );
    }

    @Test
    void exposesTruthfulLegacyRuleTraceability() throws Exception {
        mockMvc.perform(get("/api/platform/risk-rules/traceability"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.risk_dictionary.length()", is(39)))
            .andExpect(jsonPath("$.fact_scoring_catalog.length()", is(11)))
            .andExpect(jsonPath("$.fact_scoring_catalog[0].risk_name", is("股权冻结")))
            .andExpect(jsonPath("$.fact_scoring_catalog[8].score_handling", is("已确认事件最低6分")))
            .andExpect(jsonPath("$.risk_dictionary[0].legacy_name", is("LAW_INFO")))
            .andExpect(jsonPath("$.risk_dictionary[38].legacy_name", is("OUT_OF_CANTACT")))
            .andExpect(jsonPath("$.active_hard_coded_labels.length()", is(16)))
            .andExpect(jsonPath("$.active_hard_coded_labels[0].legacy_label_no", is("103112120")))
            .andExpect(jsonPath("$.feature_requirements.length()", is(10)))
            .andExpect(jsonPath("$.feature_requirements[8].readiness", is("缺失")))
            .andExpect(jsonPath("$.calculation_rules.length()", is(15)))
            .andExpect(jsonPath("$.calculation_rules[3].migration_status", is("已纠错迁移")))
            .andExpect(jsonPath("$.calculation_rules[10].migration_status", is("V1不恢复")))
            .andExpect(jsonPath("$.calculation_rules[12].migration_status", is("V1审计修正")))
            .andExpect(jsonPath("$.current_runtime_mode", is(
                "FEATURE_COMPLETE_RECALCULATE_OTHERWISE_MATERIALIZED_FALLBACK"
            )));
    }

    @Test
    void requiresSchemaAndTwentyPassingReplaySamplesBeforePublishing() throws Exception {
        JsonNode initialized = json(postOk(
            "/api/platform/risk-rules/initialize",
            Map.of("operator_id", "rule-admin")
        ));
        String versionId = initialized.path("versions").get(0).path("version_id").asText();

        JsonNode smallReplay = json(postOk(
            "/api/platform/risk-rules/versions/" + versionId + "/replays",
            Map.of("operator_id", "rule-admin", "samples", samples(5, false))
        ));
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", smallReplay.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(5, smallReplay.path("passed_count").asInt());

        JsonNode firstPassingReplay = json(postOk(
            "/api/platform/risk-rules/versions/" + versionId + "/replays",
            Map.of("operator_id", "rule-admin", "samples", samples(20, false))
        ));
        org.junit.jupiter.api.Assertions.assertEquals("PASSED", firstPassingReplay.path("status").asText());

        String policyJson = initialized.path("versions").get(0).path("value_json").asText()
            .replace("\"risk_event_days\":365", "\"risk_event_days\":360")
            .replace("\"LEGACY_NEGATIVE_SENTIMENT\":2.4", "\"LEGACY_NEGATIVE_SENTIMENT\":1.1");
        JsonNode updated = json(putOk(
            "/api/platform/configurations/versions/" + versionId,
            Map.of(
                "expected_row_version", 0,
                "value_json", policyJson,
                "operator_id", "rule-admin"
            )
        ));
        org.junit.jupiter.api.Assertions.assertEquals(1, updated.path("row_version").asInt());

        postOk(
            "/api/platform/configurations/versions/" + versionId + "/validate",
            Map.of("expected_row_version", 1, "operator_id", "rule-admin")
        );
        mockMvc.perform(post("/api/platform/configurations/versions/" + versionId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "environment", "DEV",
                    "idempotency_key", "publish-rules-with-stale-replay",
                    "operator_id", "rule-admin"
                ))))
            .andExpect(status().isConflict());

        JsonNode passingReplay = json(postOk(
            "/api/platform/risk-rules/versions/" + versionId + "/replays",
            Map.of("operator_id", "rule-admin", "samples", samples(20, true))
        ));
        org.junit.jupiter.api.Assertions.assertEquals("PASSED", passingReplay.path("status").asText());
        postOk(
            "/api/platform/configurations/versions/" + versionId + "/publish",
            Map.of(
                "environment", "DEV",
                "idempotency_key", "publish-rules-after-replay",
                "operator_id", "rule-admin"
            )
        );

        String taskId = createTask();
        mockMvc.perform(get(
                "/api/platform/risk-rules/versions/{versionId}/usage", versionId
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_usage_count", is(1)));
        String manifest = taskSnapshot(taskId).path("manifest_json").asText();
        org.junit.jupiter.api.Assertions.assertTrue(manifest.contains(versionId));
        org.junit.jupiter.api.Assertions.assertTrue(
            policyResolver.resolve(java.util.UUID.fromString(taskId)).version()
                .startsWith("risk.rules.v1/v1@")
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            0,
            policyResolver.resolve(java.util.UUID.fromString(taskId))
                .floorFor(RiskType.STORE_CLOSURE)
                .compareTo(new java.math.BigDecimal("8"))
        );

        mockMvc.perform(get("/api/platform/risk-rules").param("environment", "DEV"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policies[0].configuration.binding.active_version_id", is(versionId)))
            .andExpect(jsonPath("$.policies[0].version_impacts[0].latest_replay.status", is("PASSED")));
    }

    private List<Map<String, Object>> samples(int count, boolean includeWeightedFeature) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            if (includeWeightedFeature && index == 1) {
                values.add(Map.of(
                    "sample_id", "golden-weighted-1",
                    "legacy_score", 0,
                    "risk_types", List.of(),
                    "expected_score", 1.1,
                    "expected_level", "LOW",
                    "features", Map.of(
                        "scoring_profile", "STANDARD",
                        "sentiment_count", 1
                    )
                ));
                continue;
            }
            values.add(Map.of(
                "sample_id", "golden-" + index,
                "legacy_score", 1,
                "risk_types", List.of(),
                "expected_score", 1,
                "expected_level", "LOW"
            ));
        }
        return values;
    }

    private String createTask() throws Exception {
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "risk-policy-snapshot-task")
                .header("X-Operator-Id", "operator-1")
                .content("""
                    {
                      "prompt":"更新规则快照测试企业风险报告",
                      "company_query":"规则快照测试企业有限公司",
                      "previous_report_file_id":"report-v1"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        return json(response).path("task_id").asText();
    }

    private JsonNode taskSnapshot(String taskId) throws Exception {
        return json(mockMvc.perform(post(
                "/api/platform/configurations/tasks/{taskId}/snapshot", taskId
            ).param("environment", "DEV"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private String postOk(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String putOk(String path, Object body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
