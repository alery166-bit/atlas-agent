package com.atlas.enterprise.acceptance.storage;

import com.atlas.enterprise.acceptance.port.GoldenAcceptanceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGoldenAcceptanceRepository implements GoldenAcceptanceRepository {
    private final JdbcClient jdbc;

    public JdbcGoldenAcceptanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Suite saveSuite(Suite suite) {
        jdbc.sql("""
                INSERT INTO golden_acceptance_suite (
                    suite_id, name, schema_version, status, case_count,
                    confirmed_case_count, verified_artifact_case_count, manifest_json, content_hash,
                    created_by, created_at
                ) VALUES (
                    :id,:name,:schemaVersion,:status,:caseCount,
                    :confirmedCaseCount,:verifiedArtifactCaseCount,:manifest,:hash,:createdBy,:createdAt
                )
                """)
            .param("id", suite.suiteId()).param("name", suite.name())
            .param("schemaVersion", suite.schemaVersion()).param("status", suite.status())
            .param("caseCount", suite.caseCount())
            .param("confirmedCaseCount", suite.confirmedCaseCount())
            .param("verifiedArtifactCaseCount", suite.verifiedArtifactCaseCount())
            .param("manifest", suite.manifestJson()).param("hash", suite.contentHash())
            .param("createdBy", suite.createdBy()).param("createdAt", time(suite.createdAt()))
            .update();
        audit(suite.createdBy(), "golden_suite.imported", "GOLDEN_SUITE",
            suite.suiteId().toString(), suite.contentHash(), suite.createdAt());
        return suite;
    }

    @Override
    public List<Suite> findSuites() {
        return jdbc.sql("SELECT * FROM golden_acceptance_suite ORDER BY created_at DESC")
            .query(this::suite).list();
    }

    @Override
    public Optional<Suite> findSuite(UUID suiteId) {
        return jdbc.sql("SELECT * FROM golden_acceptance_suite WHERE suite_id=:id")
            .param("id", suiteId).query(this::suite).optional();
    }

    @Override
    @Transactional
    public Run saveRun(Run run) {
        jdbc.sql("""
                INSERT INTO golden_acceptance_run (
                    run_id, suite_id, status, case_count, completed_case_count,
                    severe_subject_mismatch_count, major_risk_count,
                    supported_major_risk_count, explainable_score_count,
                    docx_pass_count, critical_defect_count, high_defect_count,
                    average_manual_minutes, result_json, operator_id, created_at
                ) VALUES (
                    :id,:suiteId,:status,:caseCount,:completedCaseCount,
                    :subjectMismatch,:majorRiskCount,:supportedMajorRiskCount,
                    :explainableScoreCount,:docxPassCount,:criticalDefectCount,
                    :highDefectCount,:averageMinutes,:resultJson,:operatorId,:createdAt
                )
                """)
            .param("id", run.runId()).param("suiteId", run.suiteId())
            .param("status", run.status()).param("caseCount", run.caseCount())
            .param("completedCaseCount", run.completedCaseCount())
            .param("subjectMismatch", run.severeSubjectMismatchCount())
            .param("majorRiskCount", run.majorRiskCount())
            .param("supportedMajorRiskCount", run.supportedMajorRiskCount())
            .param("explainableScoreCount", run.explainableScoreCount())
            .param("docxPassCount", run.docxPassCount())
            .param("criticalDefectCount", run.criticalDefectCount())
            .param("highDefectCount", run.highDefectCount())
            .param("averageMinutes", run.averageManualMinutes())
            .param("resultJson", run.resultJson()).param("operatorId", run.operatorId())
            .param("createdAt", time(run.createdAt())).update();
        audit(run.operatorId(), "golden_acceptance.evaluated", "GOLDEN_RUN",
            run.runId().toString(), run.status() + ":" + run.completedCaseCount(), run.createdAt());
        return run;
    }

    @Override
    public List<Run> findRuns(UUID suiteId) {
        return jdbc.sql("""
                SELECT * FROM golden_acceptance_run
                 WHERE suite_id=:suiteId ORDER BY created_at DESC
                """).param("suiteId", suiteId).query(this::run).list();
    }

    private void audit(
        String operatorId, String action, String targetType,
        String targetId, String digest, Instant at
    ) {
        jdbc.sql("""
                INSERT INTO audit_event (
                    task_id, trace_id, actor_type, actor_id, action,
                    target_type, target_id, payload_digest, occurred_at
                ) VALUES (
                    NULL,:traceId,'OPERATOR',:operatorId,:action,
                    :targetType,:targetId,:digest,:occurredAt
                )
                """).param("traceId", "golden:" + targetId).param("operatorId", operatorId)
            .param("action", action).param("targetType", targetType)
            .param("targetId", targetId).param("digest", digest)
            .param("occurredAt", time(at)).update();
    }

    private Suite suite(ResultSet rs, int row) throws SQLException {
        return new Suite(
            uuid(rs, "suite_id"), rs.getString("name"), rs.getString("schema_version"),
            rs.getString("status"), rs.getInt("case_count"),
            rs.getInt("confirmed_case_count"), rs.getInt("verified_artifact_case_count"),
            rs.getString("manifest_json"),
            rs.getString("content_hash"), rs.getString("created_by"),
            instant(rs, "created_at")
        );
    }

    private Run run(ResultSet rs, int row) throws SQLException {
        return new Run(
            uuid(rs, "run_id"), uuid(rs, "suite_id"), rs.getString("status"),
            rs.getInt("case_count"), rs.getInt("completed_case_count"),
            rs.getInt("severe_subject_mismatch_count"), rs.getInt("major_risk_count"),
            rs.getInt("supported_major_risk_count"), rs.getInt("explainable_score_count"),
            rs.getInt("docx_pass_count"), rs.getInt("critical_defect_count"),
            rs.getInt("high_defect_count"), rs.getBigDecimal("average_manual_minutes"),
            rs.getString("result_json"), rs.getString("operator_id"), instant(rs, "created_at")
        );
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getObject(column).toString());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
