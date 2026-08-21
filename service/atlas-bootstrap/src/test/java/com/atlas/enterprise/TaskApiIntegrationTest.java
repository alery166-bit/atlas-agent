package com.atlas.enterprise;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsAndReadsAnIdempotentTask() throws Exception {
        String body = """
            {
              "prompt": "更新北京简熹和食品有限公司的风险报告",
              "company_query": "北京简熹和食品有限公司",
              "previous_report_file_id": "report-v1"
            }
            """;

        String first = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "integration-task-1")
                .header("X-Operator-Id", "operator-1")
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(jsonPath("$.status", is("CREATED")))
            .andExpect(jsonPath("$.intent", is("RISK_REPORT_UPDATE")))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode firstJson = objectMapper.readTree(first);
        String taskId = firstJson.path("task_id").asText();

        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "integration-task-1")
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.task_id", is(taskId)));

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.task_id", is(taskId)))
            .andExpect(jsonPath("$.data_snapshot").doesNotExist())
            .andExpect(jsonPath("$.risk_score").doesNotExist())
            .andExpect(jsonPath("$.previous_report_score").doesNotExist())
            .andExpect(jsonPath("$.evidence_progress.total", is(0)))
            .andExpect(jsonPath("$.confirmation_state", is("NOT_READY")))
            .andExpect(jsonPath("$.confirmation_ready", is(false)))
            .andExpect(jsonPath(
                "$.readiness_blockers.length()",
                is(2)
            ))
            .andExpect(jsonPath("$.next_action", is("EXECUTE_TASK")))
            .andExpect(jsonPath("$.reports.length()", is(0)))
            .andExpect(jsonPath("$.steps.length()", is(0)));

        mockMvc.perform(get("/api/tasks/{taskId}", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_id", is(taskId)))
            .andExpect(jsonPath("$.company_query", is("北京简熹和食品有限公司")));
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "invalid-task")
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "malformed-task")
                .content("{\\\"prompt\\\":\\\"invalid\\:json\\\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
            .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void returnsNotFoundForUnknownTaskWorkspace() throws Exception {
        mockMvc.perform(get(
                    "/api/tasks/00000000-0000-0000-0000-000000000001/workspace"
                ))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code", is("TASK_NOT_FOUND")));
    }

    @Test
    void executesOfflineWorkflowFreezesSnapshotAndCompletesReport() throws Exception {
        String taskId = createTask(
            "w2-jianxi-success",
            "北京简熹和食品有限公司",
            "更新北京简熹和食品有限公司的风险报告"
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId)
                .header("X-Trace-Id", "w2-success-trace"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("COMPLETED")))
            .andExpect(jsonPath("$.atlas_company_id", notNullValue()))
            .andExpect(jsonPath("$.current_step", is("COMPLETED")));

        mockMvc.perform(get("/api/tasks/{taskId}/snapshot", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.company_facts.canonical_name", is("北京简熹和食品有限公司")))
            .andExpect(jsonPath("$.company_facts.unified_credit_code", is("91110113MAK5DEJQ0W")))
            .andExpect(jsonPath("$.company_changes.length()", is(1)))
            .andExpect(jsonPath("$.risk_events.length()", is(1)))
            .andExpect(jsonPath("$.risk_events[0].event_type", is("BUSINESS_ABNORMAL")))
            .andExpect(jsonPath("$.content_hash", notNullValue()));

        mockMvc.perform(get("/api/tasks/{taskId}/steps", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(3)))
            .andExpect(jsonPath("$[0].status", is("COMPLETED")))
            .andExpect(jsonPath("$[1].status", is("COMPLETED")));

        mockMvc.perform(get("/api/tasks/{taskId}/reports", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].status", is("GENERATED")));
    }

    @Test
    void blocksOnSubjectDataConflictAndResumesAfterAuditedMasterAcceptance()
        throws Exception {
        String taskId = createTask(
            "w25-subject-data-conflict",
            "北京主体冲突测试有限公司",
            "生成北京主体冲突测试有限公司的风险报告"
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("WAITING_SUBJECT_DATA_REVIEW")))
            .andExpect(jsonPath("$.current_step", is("REVIEW_SUBJECT_DATA")));

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subject_data_conflicts.length()", is(1)))
            .andExpect(jsonPath(
                "$.subject_data_conflicts[0].code",
                is("LEGAL_REPRESENTATIVE_CONFLICT")
            ))
            .andExpect(jsonPath("$.subject_data_conflict_resolution").doesNotExist())
            .andExpect(jsonPath("$.readiness_blockers", hasItem("SUBJECT_DATA_CONFLICT")))
            .andExpect(jsonPath("$.next_action", is("REVIEW_SUBJECT_DATA")))
            .andExpect(jsonPath("$.reports.length()", is(0)));

        mockMvc.perform(post(
                    "/api/tasks/{taskId}/subject-data-conflict-resolution",
                    taskId
                )
                .header("X-Operator-Id", "operator-subject-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision": "ACCEPT_MASTER",
                      "note": "已核对权威公示信息，确认当前企业主档为准"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status", is("COMPLETED")))
            .andExpect(jsonPath(
                "$.subject_data_conflict_resolution.decision",
                is("ACCEPT_MASTER")
            ))
            .andExpect(jsonPath(
                "$.subject_data_conflict_resolution.operator_id",
                is("operator-subject-review")
            ))
            .andExpect(jsonPath(
                "$.readiness_blockers",
                not(hasItem("SUBJECT_DATA_CONFLICT"))
            ))
            .andExpect(jsonPath("$.confirmation_state", is("VALID")))
            .andExpect(jsonPath("$.next_action", is("DOWNLOAD_REPORT")))
            .andExpect(jsonPath("$.reports.length()", is(1)));

        mockMvc.perform(get("/api/platform/audit")
                .param("task_id", taskId)
                .param("operator_id", "operator-subject-review")
                .param("event_type", "SUBJECT_DATA_CONFLICT_RESOLUTION"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].action", is("subject.data.conflict.resolved")))
            .andExpect(jsonPath("$[0].target_type", is("DATA_SNAPSHOT")));
    }

    @Test
    void automaticallyResumesWhenNewerMasterSupersedesHistoricalChange()
        throws Exception {
        String taskId = createTask(
            "w25-subject-data-freshness-recovery",
            "北京主体冲突测试有限公司",
            "生成北京主体冲突测试有限公司的风险报告"
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("WAITING_SUBJECT_DATA_REVIEW")));

        String factsJson = jdbcTemplate.queryForObject(
            """
            SELECT company_facts_json
              FROM data_snapshot
             WHERE task_id = ?
             ORDER BY snapshot_version DESC
             LIMIT 1
            """,
            String.class,
            java.util.UUID.fromString(taskId)
        );
        ObjectNode facts = (ObjectNode) objectMapper.readTree(factsJson);
        facts.put("data_as_of", "2026-08-01T00:00:00Z");
        jdbcTemplate.update(
            "UPDATE data_snapshot SET company_facts_json = ? WHERE task_id = ?",
            objectMapper.writeValueAsString(facts),
            java.util.UUID.fromString(taskId)
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("COMPLETED")))
            .andExpect(jsonPath("$.current_step", is("COMPLETED")));

        mockMvc.perform(get("/api/tasks/{taskId}/workspace", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subject_data_conflicts.length()", is(0)))
            .andExpect(jsonPath("$.next_action", is("DOWNLOAD_REPORT")))
            .andExpect(jsonPath("$.reports.length()", is(1)));
    }

    @Test
    void waitsForOperatorWhenExactNameHasMultipleSubjects() throws Exception {
        String taskId = createTask(
            "w2-ambiguous-subject",
            "北京同名测试有限公司",
            "更新北京同名测试有限公司的风险报告"
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("WAITING_SUBJECT_CONFIRMATION")))
            .andExpect(jsonPath("$.atlas_company_id").doesNotExist());

        mockMvc.perform(get("/api/companies/resolve")
                .param("query", "北京同名测试有限公司"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("AMBIGUOUS")))
            .andExpect(jsonPath("$.candidates.length()", is(2)));

        mockMvc.perform(post("/api/tasks/{taskId}/subject-confirmation", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "operator-1")
                .content("""
                    {
                      "source_system": "OFFLINE_CSV",
                      "source_entity_id": "q-ambiguous-a"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("COMPLETED")))
            .andExpect(jsonPath("$.atlas_company_id", notNullValue()));

        mockMvc.perform(get("/api/tasks/{taskId}/snapshot", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.risk_events.length()", is(0)));
    }

    @Test
    void stopsWhenSubjectIsNotFound() throws Exception {
        String taskId = createTask(
            "w2-subject-not-found",
            "不存在的测试企业有限公司",
            "更新不存在的测试企业有限公司的风险报告"
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("SOURCE_FAILED")))
            .andExpect(jsonPath("$.failed_step", is("RESOLVE_SUBJECT")))
            .andExpect(jsonPath("$.error_code", is("SUBJECT_NOT_FOUND")));

        mockMvc.perform(post("/api/tasks/{taskId}/retry", taskId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("TASK_STATE_CONFLICT")));
    }

    @Test
    void resolvesAndSnapshotsConfiguredJsonSample() throws Exception {
        String taskId = createTask(
            "w2-json-provider",
            "JSON样本企业有限公司",
            "更新JSON样本企业有限公司的风险报告"
        );

        mockMvc.perform(post("/api/tasks/{taskId}/execute", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("CALCULATING_RISK")));

        mockMvc.perform(get("/api/tasks/{taskId}/snapshot", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.company_facts.source_system", is("OFFLINE_JSON")))
            .andExpect(jsonPath("$.risk_events[0].event_type", is("WAGE_ARREARS")));
    }

    @Test
    void listsTasksWithFiltersAndStableCursorPagination() throws Exception {
        String operatorId = "list-operator";
        String alpha = createTask(
            "w9-list-alpha",
            "Atlas List Alpha Company",
            "Update Atlas List Alpha Company risk report",
            operatorId
        );
        String beta = createTask(
            "w9-list-beta",
            "Atlas List Beta Company",
            "Update Atlas List Beta Company risk report",
            operatorId
        );
        String gamma = createTask(
            "w9-list-gamma",
            "Atlas List Gamma Company",
            "Update Atlas List Gamma Company risk report",
            operatorId
        );

        String firstBody = mockMvc.perform(get("/api/tasks")
                .param("operator_id", operatorId)
                .param("status", "CREATED")
                .param("page_size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()", is(2)))
            .andExpect(jsonPath("$.has_more", is(true)))
            .andExpect(jsonPath("$.next_cursor", notNullValue()))
            .andExpect(jsonPath(
                "$.items[0].task.operator_id",
                is(operatorId)
            ))
            .andExpect(jsonPath(
                "$.items[0].confirmation_state",
                is("NOT_READY")
            ))
            .andExpect(jsonPath(
                "$.items[0].evidence_progress.total",
                is(0)
            ))
            .andExpect(jsonPath(
                "$.items[0].next_action",
                is("EXECUTE_TASK")
            ))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode first = objectMapper.readTree(firstBody);
        String cursor = first.path("next_cursor").asText();

        String secondBody = mockMvc.perform(get("/api/tasks")
                .param("operator_id", operatorId)
                .param("status", "CREATED")
                .param("page_size", "2")
                .param("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()", is(1)))
            .andExpect(jsonPath("$.has_more", is(false)))
            .andExpect(jsonPath("$.next_cursor").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode second = objectMapper.readTree(secondBody);

        Set<String> listedIds = new HashSet<>();
        first.path("items").forEach(item ->
            listedIds.add(item.path("task").path("task_id").asText())
        );
        second.path("items").forEach(item ->
            listedIds.add(item.path("task").path("task_id").asText())
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            Set.of(alpha, beta, gamma),
            listedIds
        );

        mockMvc.perform(get("/api/tasks")
                .param("operator_id", operatorId)
                .param("query", "Alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()", is(1)))
            .andExpect(jsonPath("$.items[0].task.task_id", is(alpha)))
            .andExpect(jsonPath(
                "$.items[0].company_name",
                is("Atlas List Alpha Company")
            ));

        mockMvc.perform(get("/api/tasks")
                .param("operator_id", operatorId)
                .param("status", "SOURCE_FAILED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()", is(0)));

        mockMvc.perform(get("/api/tasks").param("cursor", "not-a-cursor"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_TASK_LIST_QUERY")));

        mockMvc.perform(get("/api/tasks").param("page_size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("INVALID_TASK_LIST_QUERY")));
    }

    private String createTask(
        String idempotencyKey,
        String companyQuery,
        String prompt
    ) throws Exception {
        return createTask(
            idempotencyKey,
            companyQuery,
            prompt,
            "local-operator"
        );
    }

    private String createTask(
        String idempotencyKey,
        String companyQuery,
        String prompt,
        String operatorId
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "prompt", prompt,
            "company_query", companyQuery,
            "previous_report_file_id", "report-v1"
        ));
        String response = mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Operator-Id", operatorId)
                .content(body))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("task_id").asText();
    }
}
