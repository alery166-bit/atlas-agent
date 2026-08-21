package com.atlas.enterprise;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.enterprise.operations.application.PlatformObservabilityService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformObservabilityApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformObservabilityService observability;

    @Test
    void exposesRealOperationsAuditExportAndRetryTrace() throws Exception {
        Instant now = Instant.now();
        UUID taskId = UUID.randomUUID();
        insertTask(taskId, now);
        UUID stepId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO task_step (
                    task_step_id, task_id, step_name, sequence_no, status, attempt_no,
                    trace_id, error_code, started_at, ended_at
                ) VALUES (:id,:taskId,'SEARCH_PUBLIC_INTELLIGENCE',4,'FAILED',1,
                          'trace-observe','SEARCH_PROVIDER_UNAVAILABLE',:started,:ended)
                """)
            .param("id", stepId).param("taskId", taskId)
            .param("started", time(now.minusSeconds(10))).param("ended", time(now)).update();

        mockMvc.perform(get("/api/platform/operations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total_tasks", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.failed_tasks", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.model_call_state", is("RECORDED")))
            .andExpect(jsonPath("$.model_calls", is(0)))
            .andExpect(jsonPath("$.model_total_tokens", is(0)))
            .andExpect(jsonPath("$.failures[0].failed_step", is("SEARCH_PUBLIC_INTELLIGENCE")))
            .andExpect(jsonPath("$.failures[0].retryable", is(true)));

        observability.recordRetry(taskId, "ops-reviewer", "retry-trace");
        mockMvc.perform(get("/api/platform/audit")
                .param("task_id", taskId.toString())
                .param("operator_id", "ops-reviewer"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].action", is("task.retry")))
            .andExpect(jsonPath("$[0].operator_id", is("ops-reviewer")));

        mockMvc.perform(get("/api/platform/audit/export")
                .param("task_id", taskId.toString()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("task.retry")));
    }

    @Test
    void separatesRecentExecutionWaitingAndStalledTasks() throws Exception {
        Instant now = Instant.now();
        insertRunningTask(UUID.randomUUID(), "CALCULATING_RISK", now.minusSeconds(60));
        UUID stalledId = UUID.randomUUID();
        insertRunningTask(stalledId, "SEARCHING_PUBLIC_INTELLIGENCE", now.minusSeconds(3600));
        insertRunningTask(UUID.randomUUID(), "WAITING_OPERATOR_CONFIRMATION", now.minusSeconds(7200));

        mockMvc.perform(get("/api/platform/operations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activity_threshold_minutes", is(15)))
            .andExpect(jsonPath("$.active_tasks", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.waiting_tasks", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.stalled_tasks", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.stalled[0].stalled_minutes", greaterThanOrEqualTo(15)));
    }

    @Test
    void filtersAuditAndReturnsConfigurationBeforeAfterComparison() throws Exception {
        Instant now = Instant.now();
        UUID configId = UUID.randomUUID();
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO configuration_definition (
                    config_id, config_key, category, display_name, description,
                    secret_config, created_by, created_at
                ) VALUES (:id,:key,'RULES','监控测试规则','test',FALSE,'auditor',:at)
                """).param("id", configId).param("key", "observe.test." + configId)
            .param("at", time(now.minusSeconds(30))).update();
        insertVersion(fromId, configId, 1, "INACTIVE", "{\"score\":6}", now.minusSeconds(20));
        insertVersion(toId, configId, 2, "PUBLISHED", "{\"score\":8}", now.minusSeconds(10));
        jdbc.sql("""
                INSERT INTO configuration_release (
                    release_id, config_id, environment, from_version_id, to_version_id,
                    action, idempotency_key, operator_id, occurred_at
                ) VALUES (:id,:configId,'DEV',:fromId,:toId,'PUBLISH',:key,'rule-admin',:at)
                """).param("id", releaseId).param("configId", configId)
            .param("fromId", fromId).param("toId", toId)
            .param("key", "observe-release-" + releaseId).param("at", time(now)).update();

        mockMvc.perform(get("/api/platform/audit")
                .param("event_type", "CONFIGURATION_RELEASE")
                .param("operator_id", "rule-admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].event_id", is(releaseId.toString())))
            .andExpect(jsonPath("$[0].before_json", is("{\"score\":6}")))
            .andExpect(jsonPath("$[0].after_json", is("{\"score\":8}")));

        mockMvc.perform(get("/api/platform/configuration-changes")
                .param("release_id", releaseId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.from_version_no", is(1)))
            .andExpect(jsonPath("$.to_version_no", is(2)))
            .andExpect(jsonPath("$.operator_id", is("rule-admin")));
    }

    private void insertTask(UUID taskId, Instant now) {
        jdbc.sql("""
                INSERT INTO investigation_task (
                    task_id, task_no, intent, status, original_prompt, company_query,
                    previous_report_file_id, operator_id, idempotency_key, current_step,
                    failed_step, error_code, created_at, updated_at
                ) VALUES (:id,:taskNo,'RISK_REPORT_UPDATE','SOURCE_FAILED','更新风险报告',
                          '监控测试企业','report-v1','ops-reviewer',:key,
                          'SEARCH_PUBLIC_INTELLIGENCE','SEARCH_PUBLIC_INTELLIGENCE',
                          'SEARCH_PROVIDER_UNAVAILABLE',:created,:updated)
                """).param("id", taskId).param("taskNo", "OBS-" + taskId.toString().substring(0, 8))
            .param("key", "observe-task-" + taskId).param("created", time(now.minusSeconds(60)))
            .param("updated", time(now)).update();
    }

    private void insertVersion(
        UUID versionId, UUID configId, int versionNo, String status,
        String json, Instant at
    ) {
        jdbc.sql("""
                INSERT INTO configuration_version (
                    version_id, config_id, version_no, status, value_json, checksum,
                    created_by, created_at, row_version
                ) VALUES (:id,:configId,:versionNo,:status,:json,:checksum,'rule-admin',:at,0)
                """).param("id", versionId).param("configId", configId)
            .param("versionNo", versionNo).param("status", status).param("json", json)
            .param("checksum", "checksum-" + versionId).param("at", time(at)).update();
    }

    private void insertRunningTask(UUID taskId, String status, Instant updatedAt) {
        jdbc.sql("""
                INSERT INTO investigation_task (
                    task_id, task_no, intent, status, original_prompt, company_query,
                    previous_report_file_id, operator_id, idempotency_key, current_step,
                    created_at, updated_at
                ) VALUES (:id,:taskNo,'RISK_REPORT_UPDATE',:status,'更新风险报告',
                          '状态口径测试企业','report-v1','ops-reviewer',:key,
                          'SEARCH_PUBLIC_INTELLIGENCE',:created,:updated)
                """).param("id", taskId).param("taskNo", "STATE-" + taskId.toString().substring(0, 8))
            .param("status", status).param("key", "state-task-" + taskId)
            .param("created", time(updatedAt.minusSeconds(60))).param("updated", time(updatedAt)).update();
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
