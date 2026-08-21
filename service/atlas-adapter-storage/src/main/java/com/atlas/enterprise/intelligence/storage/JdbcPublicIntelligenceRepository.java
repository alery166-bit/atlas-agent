package com.atlas.enterprise.intelligence.storage;

import com.atlas.enterprise.intelligence.EntityMatchStatus;
import com.atlas.enterprise.intelligence.EvidenceContentSnapshot;
import com.atlas.enterprise.intelligence.EvidenceContentReference;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.EvidenceDecision;
import com.atlas.enterprise.intelligence.EvidenceGrade;
import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.SearchBatchStatus;
import com.atlas.enterprise.intelligence.SearchExecution;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.risk.RiskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPublicIntelligenceRepository implements PublicIntelligenceRepository {
    private static final String SEARCH_SELECT = """
        SELECT search_batch_id, task_id, source_snapshot_id, provider, provider_mode,
               query_text, target_risk, source_scope, status, result_count, failure_code,
               failure_message, searched_at
          FROM public_search_batch
        """;
    private static final String EVIDENCE_SELECT = """
        SELECT evidence_id, task_id, atlas_company_id, search_batch_id, risk_type,
               source_provider, source_url, normalized_url, source_domain, title,
               snippet, query_text, rank_no, published_at, captured_at, content_hash,
               entity_match_status, verification_status, evidence_grade,
               raw_metadata_json
          FROM evidence
         WHERE evidence_type = 'PUBLIC_SEARCH_RESULT'
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPublicIntelligenceRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchExecution saveSearch(SearchExecution search) {
        jdbc.sql("""
                INSERT INTO public_search_batch (
                    search_batch_id, task_id, source_snapshot_id, provider, provider_mode,
                    query_text, target_risk, source_scope, status, result_count, failure_code,
                    failure_message, searched_at
                ) VALUES (
                    :searchBatchId, :taskId, :snapshotId, :provider, :providerMode,
                    :queryText, :targetRisk, :sourceScope, :status, :resultCount, :failureCode,
                    :failureMessage, :searchedAt
                )
                """)
            .param("searchBatchId", search.searchBatchId())
            .param("taskId", search.taskId())
            .param("snapshotId", search.snapshotId())
            .param("provider", search.provider())
            .param("providerMode", search.providerMode().name())
            .param("queryText", search.query())
            .param("targetRisk", search.targetRisk().name())
            .param("sourceScope", search.sourceScope())
            .param("status", search.status().name())
            .param("resultCount", search.resultCount())
            .param("failureCode", search.failureCode())
            .param("failureMessage", search.failureMessage())
            .param(
                "searchedAt",
                OffsetDateTime.ofInstant(search.searchedAt(), ZoneOffset.UTC)
            )
            .update();
        return search;
    }

    @Override
    public PublicEvidence saveEvidence(PublicEvidence evidence) {
        jdbc.sql("""
                INSERT INTO evidence (
                    evidence_id, task_id, atlas_company_id, evidence_type,
                    source_provider, source_url, normalized_url, title, published_at,
                    captured_at, content_hash, entity_match_status, verification_status,
                    storage_uri, snippet, raw_metadata_json, search_batch_id, risk_type,
                    source_domain, query_text, rank_no, evidence_grade
                ) VALUES (
                    :evidenceId, :taskId, :companyId, 'PUBLIC_SEARCH_RESULT',
                    :provider, :sourceUrl, :normalizedUrl, :title, :publishedAt,
                    :capturedAt, :contentHash, :entityMatch, :verification,
                    NULL, :snippet, :metadata, :searchBatchId, :riskType,
                    :sourceDomain, :queryText, :rankNo, :evidenceGrade
                )
                """)
            .param("evidenceId", evidence.evidenceId())
            .param("taskId", evidence.taskId())
            .param("companyId", evidence.atlasCompanyId())
            .param("provider", evidence.sourceProvider())
            .param("sourceUrl", evidence.sourceUrl())
            .param("normalizedUrl", evidence.normalizedUrl())
            .param("title", evidence.title())
            .param(
                "publishedAt",
                evidence.publishedAt() == null
                    ? null
                    : OffsetDateTime.ofInstant(evidence.publishedAt(), ZoneOffset.UTC)
            )
            .param(
                "capturedAt",
                OffsetDateTime.ofInstant(evidence.capturedAt(), ZoneOffset.UTC)
            )
            .param("contentHash", evidence.contentHash())
            .param("entityMatch", evidence.entityMatchStatus().name())
            .param("verification", evidence.verificationStatus().name())
            .param("snippet", evidence.snippet())
            .param("metadata", json(evidence.metadata()))
            .param("searchBatchId", evidence.searchBatchId())
            .param("riskType", evidence.riskType().name())
            .param("sourceDomain", evidence.sourceDomain())
            .param("queryText", evidence.query())
            .param("rankNo", evidence.rank())
            .param("evidenceGrade", evidence.grade().name())
            .update();
        return evidence;
    }

    @Override
    public PublicEvidence updateEvidence(PublicEvidence evidence) {
        int updated = jdbc.sql("""
                UPDATE evidence
                   SET risk_type = :riskType,
                       verification_status = :verification,
                       raw_metadata_json = :metadata
                 WHERE evidence_id = :evidenceId
                """)
            .param("riskType", evidence.riskType().name())
            .param("verification", evidence.verificationStatus().name())
            .param("metadata", json(evidence.metadata()))
            .param("evidenceId", evidence.evidenceId())
            .update();
        if (updated != 1) {
            throw new IllegalStateException(
                "Evidence update affected " + updated + " rows"
            );
        }
        return evidence;
    }

    @Override
    public EvidenceDecision saveDecision(EvidenceDecision decision) {
        jdbc.sql("""
                INSERT INTO evidence_decision (
                    decision_id, task_id, evidence_id, decision, reason,
                    operator_id, decided_at
                ) VALUES (
                    :decisionId, :taskId, :evidenceId, :decision, :reason,
                    :operatorId, :decidedAt
                )
                """)
            .param("decisionId", decision.decisionId())
            .param("taskId", decision.taskId())
            .param("evidenceId", decision.evidenceId())
            .param("decision", decision.decision().name())
            .param("reason", decision.reason())
            .param("operatorId", decision.operatorId())
            .param(
                "decidedAt",
                OffsetDateTime.ofInstant(decision.decidedAt(), ZoneOffset.UTC)
            )
            .update();
        return decision;
    }

    @Override
    public EvidenceContentSnapshot saveContentSnapshot(
        EvidenceContentSnapshot snapshot
    ) {
        jdbc.sql("""
                INSERT INTO evidence_content_snapshot (
                    content_snapshot_id, task_id, evidence_id, status,
                    requested_url, final_url, http_status, content_type,
                    raw_content, extracted_text, raw_content_hash,
                    extracted_text_hash, byte_length, truncated, failure_code,
                    failure_message, captured_at
                ) VALUES (
                    :snapshotId, :taskId, :evidenceId, :status,
                    :requestedUrl, :finalUrl, :httpStatus, :contentType,
                    :rawContent, :extractedText, :rawHash,
                    :textHash, :byteLength, :truncated, :failureCode,
                    :failureMessage, :capturedAt
                )
                """)
            .param("snapshotId", snapshot.contentSnapshotId())
            .param("taskId", snapshot.taskId())
            .param("evidenceId", snapshot.evidenceId())
            .param("status", snapshot.status().name())
            .param("requestedUrl", snapshot.requestedUrl())
            .param("finalUrl", snapshot.finalUrl())
            .param("httpStatus", snapshot.httpStatus())
            .param("contentType", snapshot.contentType())
            .param("rawContent", snapshot.rawContent())
            .param("extractedText", snapshot.extractedText())
            .param("rawHash", snapshot.rawContentHash())
            .param("textHash", snapshot.extractedTextHash())
            .param("byteLength", snapshot.byteLength())
            .param("truncated", snapshot.truncated())
            .param("failureCode", snapshot.failureCode())
            .param("failureMessage", snapshot.failureMessage())
            .param(
                "capturedAt",
                OffsetDateTime.ofInstant(snapshot.capturedAt(), ZoneOffset.UTC)
            )
            .update();
        return snapshot;
    }

    @Override
    public List<SearchExecution> findSearchesByTaskId(UUID taskId) {
        return jdbc.sql(SEARCH_SELECT + """
                 WHERE task_id = :taskId
                 ORDER BY searched_at, search_batch_id
                """)
            .param("taskId", taskId)
            .query(this::mapSearch)
            .list();
    }

    @Override
    public List<PublicEvidence> findEvidenceByTaskId(UUID taskId) {
        return jdbc.sql(EVIDENCE_SELECT + """
                   AND task_id = :taskId
                 ORDER BY captured_at, evidence_id
                """)
            .param("taskId", taskId)
            .query(this::mapEvidence)
            .list();
    }

    @Override
    public List<PublicEvidence> findEvidenceByTaskIds(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(EVIDENCE_SELECT + """
                   AND task_id IN (:taskIds)
                 ORDER BY task_id, captured_at, evidence_id
                """)
            .param("taskIds", taskIds)
            .query(this::mapEvidence)
            .list();
    }

    @Override
    public Optional<PublicEvidence> findEvidenceById(UUID evidenceId) {
        return jdbc.sql(EVIDENCE_SELECT + " AND evidence_id = :evidenceId")
            .param("evidenceId", evidenceId)
            .query(this::mapEvidence)
            .optional();
    }

    @Override
    public List<EvidenceDecision> findDecisionsByTaskId(UUID taskId) {
        return jdbc.sql("""
                SELECT decision_id, task_id, evidence_id, decision, reason,
                       operator_id, decided_at
                  FROM evidence_decision
                 WHERE task_id = :taskId
                 ORDER BY decided_at, decision_id
                """)
            .param("taskId", taskId)
            .query(this::mapDecision)
            .list();
    }

    @Override
    public List<EvidenceContentSnapshot> findContentSnapshotsByTaskId(
        UUID taskId
    ) {
        return jdbc.sql("""
                SELECT content_snapshot_id, task_id, evidence_id, status,
                       requested_url, final_url, http_status, content_type,
                       raw_content, extracted_text, raw_content_hash,
                       extracted_text_hash, byte_length, truncated, failure_code,
                       failure_message, captured_at
                  FROM evidence_content_snapshot
                 WHERE task_id = :taskId
                 ORDER BY captured_at, content_snapshot_id
                """)
            .param("taskId", taskId)
            .query(this::mapContentSnapshot)
            .list();
    }

    @Override
    public List<EvidenceContentReference> findContentReferencesByTaskId(
        UUID taskId
    ) {
        return jdbc.sql("""
                SELECT content_snapshot_id, task_id, evidence_id, status,
                       raw_content_hash, extracted_text_hash, truncated,
                       captured_at
                  FROM evidence_content_snapshot
                 WHERE task_id = :taskId
                 ORDER BY captured_at, content_snapshot_id
                """)
            .param("taskId", taskId)
            .query(this::mapContentReference)
            .list();
    }

    @Override
    public List<EvidenceContentReference> findContentReferencesByTaskIds(
        List<UUID> taskIds
    ) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT content_snapshot_id, task_id, evidence_id, status,
                       raw_content_hash, extracted_text_hash, truncated,
                       captured_at
                  FROM evidence_content_snapshot
                 WHERE task_id IN (:taskIds)
                 ORDER BY task_id, captured_at, content_snapshot_id
                """)
            .param("taskIds", taskIds)
            .query(this::mapContentReference)
            .list();
    }

    @Override
    public Optional<EvidenceContentSnapshot> findLatestContentSnapshot(
        UUID evidenceId
    ) {
        return jdbc.sql("""
                SELECT content_snapshot_id, task_id, evidence_id, status,
                       requested_url, final_url, http_status, content_type,
                       raw_content, extracted_text, raw_content_hash,
                       extracted_text_hash, byte_length, truncated, failure_code,
                       failure_message, captured_at
                  FROM evidence_content_snapshot
                 WHERE evidence_id = :evidenceId
                 ORDER BY captured_at DESC, content_snapshot_id DESC
                 LIMIT 1
                """)
            .param("evidenceId", evidenceId)
            .query(this::mapContentSnapshot)
            .optional();
    }

    private SearchExecution mapSearch(ResultSet rs, int rowNum) throws SQLException {
        return new SearchExecution(
            rs.getObject("search_batch_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("source_snapshot_id", UUID.class),
            rs.getString("provider"),
            ProviderCapabilities.ProviderMode.valueOf(rs.getString("provider_mode")),
            rs.getString("query_text"),
            RiskType.valueOf(rs.getString("target_risk")),
            rs.getString("source_scope"),
            SearchBatchStatus.valueOf(rs.getString("status")),
            rs.getInt("result_count"),
            rs.getString("failure_code"),
            rs.getString("failure_message"),
            rs.getObject("searched_at", OffsetDateTime.class).toInstant()
        );
    }

    private PublicEvidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
        return new PublicEvidence(
            rs.getObject("evidence_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("atlas_company_id", UUID.class),
            rs.getObject("search_batch_id", UUID.class),
            RiskType.valueOf(rs.getString("risk_type")),
            rs.getString("source_provider"),
            rs.getString("source_url"),
            rs.getString("normalized_url"),
            rs.getString("source_domain"),
            rs.getString("title"),
            rs.getString("snippet"),
            rs.getString("query_text"),
            rs.getInt("rank_no"),
            publishedAt == null ? null : publishedAt.toInstant(),
            rs.getObject("captured_at", OffsetDateTime.class).toInstant(),
            rs.getString("content_hash"),
            EntityMatchStatus.valueOf(rs.getString("entity_match_status")),
            EvidenceVerificationStatus.valueOf(rs.getString("verification_status")),
            EvidenceGrade.valueOf(rs.getString("evidence_grade")),
            readMetadata(rs.getString("raw_metadata_json"))
        );
    }

    private EvidenceDecision mapDecision(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceDecision(
            rs.getObject("decision_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("evidence_id", UUID.class),
            EvidenceVerificationStatus.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getString("operator_id"),
            rs.getObject("decided_at", OffsetDateTime.class).toInstant()
        );
    }

    private EvidenceContentSnapshot mapContentSnapshot(
        ResultSet rs,
        int rowNum
    ) throws SQLException {
        return new EvidenceContentSnapshot(
            rs.getObject("content_snapshot_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("evidence_id", UUID.class),
            EvidenceContentStatus.valueOf(rs.getString("status")),
            rs.getString("requested_url"),
            rs.getString("final_url"),
            rs.getObject("http_status", Integer.class),
            rs.getString("content_type"),
            rs.getString("raw_content"),
            rs.getString("extracted_text"),
            rs.getString("raw_content_hash"),
            rs.getString("extracted_text_hash"),
            rs.getLong("byte_length"),
            rs.getBoolean("truncated"),
            rs.getString("failure_code"),
            rs.getString("failure_message"),
            rs.getObject("captured_at", OffsetDateTime.class).toInstant()
        );
    }

    private EvidenceContentReference mapContentReference(
        ResultSet rs,
        int rowNum
    ) throws SQLException {
        return new EvidenceContentReference(
            rs.getObject("content_snapshot_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("evidence_id", UUID.class),
            EvidenceContentStatus.valueOf(rs.getString("status")),
            rs.getString("raw_content_hash"),
            rs.getString("extracted_text_hash"),
            rs.getBoolean("truncated"),
            rs.getObject("captured_at", OffsetDateTime.class).toInstant()
        );
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize evidence metadata", exception);
        }
    }

    private Map<String, String> readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                value,
                new TypeReference<Map<String, String>>() {}
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize evidence metadata", exception);
        }
    }
}
