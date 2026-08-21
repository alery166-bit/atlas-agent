package com.atlas.enterprise.report.storage;

import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.ReportDiff;
import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.report.port.ReportVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportVersionRepository implements ReportVersionRepository {
    private static final String SELECT = """
        SELECT report_id, task_id, atlas_company_id, template_version,
               report_version_no, status, previous_report_uri,
               generated_report_uri, input_hash, content_hash, mime_type,
               file_size, data_snapshot_id, score_snapshot_id,
               operator_confirmation_id,
               parsed_previous_json, diff_json, failure_reason,
               generated_at, generated_by
          FROM report_version
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcReportVersionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReportVersion save(ReportVersion report) {
        int updated = jdbc.sql("""
                UPDATE report_version
                   SET status = :status,
                       generated_report_uri = :generatedReportUri,
                       content_hash = :contentHash,
                       mime_type = :mimeType,
                       file_size = :fileSize,
                       parsed_previous_json = :parsedPrevious,
                       diff_json = :diff,
                       failure_reason = :failureReason,
                       generated_at = :generatedAt,
                       generated_by = :generatedBy
                 WHERE report_id = :reportId
                """)
            .param("status", report.status().name())
            .param("generatedReportUri", report.generatedReportUri())
            .param("contentHash", report.contentHash())
            .param("mimeType", report.mimeType())
            .param("fileSize", report.fileSize())
            .param("parsedPrevious", json(report.parsedPreviousReport()))
            .param("diff", json(report.diff()))
            .param("failureReason", report.failureReason())
            .param("generatedAt", utc(report.generatedAt()))
            .param("generatedBy", report.generatedBy())
            .param("reportId", report.reportId())
            .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO report_version (
                        report_id, task_id, atlas_company_id, template_version,
                        report_version_no, status, previous_report_uri,
                        generated_report_uri, input_hash, content_hash, mime_type,
                        file_size, data_snapshot_id, score_snapshot_id,
                        operator_confirmation_id,
                        parsed_previous_json, diff_json, failure_reason,
                        generated_at, generated_by
                    ) VALUES (
                        :reportId, :taskId, :companyId, :templateVersion,
                        :versionNo, :status, :previousReportUri,
                        :generatedReportUri, :inputHash, :contentHash, :mimeType,
                        :fileSize, :dataSnapshotId, :scoreSnapshotId,
                        :operatorConfirmationId,
                        :parsedPrevious, :diff, :failureReason,
                        :generatedAt, :generatedBy
                    )
                    """)
                .param("reportId", report.reportId())
                .param("taskId", report.taskId())
                .param("companyId", report.atlasCompanyId())
                .param("templateVersion", report.templateVersion())
                .param("versionNo", report.reportVersionNo())
                .param("status", report.status().name())
                .param("previousReportUri", report.previousReportUri())
                .param("generatedReportUri", report.generatedReportUri())
                .param("inputHash", report.inputHash())
                .param("contentHash", report.contentHash())
                .param("mimeType", report.mimeType())
                .param("fileSize", report.fileSize())
                .param("dataSnapshotId", report.dataSnapshotId())
                .param("scoreSnapshotId", report.scoreSnapshotId())
                .param("operatorConfirmationId", report.operatorConfirmationId())
                .param("parsedPrevious", json(report.parsedPreviousReport()))
                .param("diff", json(report.diff()))
                .param("failureReason", report.failureReason())
                .param("generatedAt", utc(report.generatedAt()))
                .param("generatedBy", report.generatedBy())
                .update();
        }
        return report;
    }

    @Override
    public Optional<ReportVersion> findById(UUID reportId) {
        return jdbc.sql(SELECT + " WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<ReportVersion> findByInputHash(UUID taskId, String inputHash) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                   AND input_hash = :inputHash
                """)
            .param("taskId", taskId)
            .param("inputHash", inputHash)
            .query(this::map)
            .optional();
    }

    @Override
    public List<ReportVersion> findByTaskId(UUID taskId) {
        return jdbc.sql(SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY report_version_no DESC
                """)
            .param("taskId", taskId)
            .query(this::map)
            .list();
    }

    @Override
    public Map<UUID, ReportVersion> findLatestByTaskIds(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT report_id, task_id, atlas_company_id, template_version,
                       report_version_no, status, previous_report_uri,
                       generated_report_uri, input_hash, content_hash,
                       mime_type, file_size, data_snapshot_id,
                       score_snapshot_id, operator_confirmation_id,
                       parsed_previous_json, diff_json, failure_reason,
                       generated_at, generated_by
                  FROM (
                      SELECT report_version.*,
                             ROW_NUMBER() OVER (
                                 PARTITION BY task_id
                                 ORDER BY report_version_no DESC
                             ) AS row_no
                        FROM report_version
                       WHERE task_id IN (:taskIds)
                  ) ranked
                 WHERE row_no = 1
                """)
            .param("taskIds", taskIds)
            .query(this::map)
            .list()
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                ReportVersion::taskId,
                Function.identity()
            ));
    }

    @Override
    public int nextVersion(UUID taskId) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(report_version_no), 0) + 1
                  FROM report_version
                 WHERE task_id = :taskId
                """)
            .param("taskId", taskId)
            .query(Integer.class)
            .single();
    }

    private ReportVersion map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime generatedAt = rs.getObject("generated_at", OffsetDateTime.class);
        Long fileSize = rs.getObject("file_size", Long.class);
        return new ReportVersion(
            rs.getObject("report_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("atlas_company_id", UUID.class),
            rs.getString("template_version"),
            rs.getInt("report_version_no"),
            ReportStatus.valueOf(rs.getString("status")),
            rs.getString("previous_report_uri"),
            rs.getString("generated_report_uri"),
            rs.getString("input_hash"),
            rs.getString("content_hash"),
            rs.getString("mime_type"),
            fileSize,
            rs.getObject("data_snapshot_id", UUID.class),
            rs.getObject("score_snapshot_id", UUID.class),
            rs.getObject("operator_confirmation_id", UUID.class),
            read(rs.getString("parsed_previous_json"), PreviousReport.class),
            read(rs.getString("diff_json"), ReportDiff.class),
            rs.getString("failure_reason"),
            generatedAt == null ? null : generatedAt.toInstant(),
            rs.getString("generated_by")
        );
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize report metadata", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize report metadata", exception);
        }
    }

    private static OffsetDateTime utc(java.time.Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
