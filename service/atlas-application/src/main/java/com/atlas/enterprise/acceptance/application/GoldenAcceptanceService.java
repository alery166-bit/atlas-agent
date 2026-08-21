package com.atlas.enterprise.acceptance.application;

import com.atlas.enterprise.acceptance.port.GoldenAcceptanceRepository;
import com.atlas.enterprise.acceptance.port.GoldenAcceptanceRepository.Run;
import com.atlas.enterprise.acceptance.port.GoldenAcceptanceRepository.Suite;
import com.atlas.enterprise.acceptance.port.GoldenArtifactVerifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GoldenAcceptanceService {
    public static final String SCHEMA_VERSION = "atlas-acceptance.v1";
    private static final int MIN_FORMAL_CASES = 20;
    private static final int MAX_CASES = 50;
    private final GoldenAcceptanceRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final GoldenArtifactVerifier artifacts;

    public GoldenAcceptanceService(
        GoldenAcceptanceRepository repository, ObjectMapper objectMapper, Clock clock,
        GoldenArtifactVerifier artifacts
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.artifacts = artifacts;
    }

    public Suite importSuite(String name, JsonNode manifest, String operatorId) {
        requireText(name, "suite name");
        requireText(operatorId, "operator id");
        if (manifest == null || !manifest.isObject()) {
            throw new IllegalArgumentException("golden manifest must be a JSON object");
        }
        if (!SCHEMA_VERSION.equals(manifest.path("schema_version").asText())) {
            throw new IllegalArgumentException("schema_version must be " + SCHEMA_VERSION);
        }
        JsonNode cases = manifest.path("cases");
        if (!cases.isArray() || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("golden suite must contain 1 to 50 cases");
        }
        Set<String> ids = new HashSet<>();
        int confirmed = 0;
        for (JsonNode sample : cases) {
            validateCase(sample, ids);
            if (sample.path("business_confirmed").asBoolean(false)) confirmed++;
        }
        String canonical = canonical(manifest);
        GoldenArtifactVerifier.Verification artifactVerification = artifacts.verify(manifest);
        boolean ready = cases.size() >= MIN_FORMAL_CASES && confirmed == cases.size()
            && artifactVerification.verifiedCaseCount() == cases.size();
        return repository.saveSuite(new Suite(
            UUID.randomUUID(), name.trim(), SCHEMA_VERSION, ready ? "READY" : "DRAFT",
            cases.size(), confirmed, artifactVerification.verifiedCaseCount(), canonical,
            sha256(canonical), operatorId.trim(), clock.instant()
        ));
    }

    public List<SuiteSummary> list() {
        return repository.findSuites().stream().map(suite -> new SuiteSummary(
            suite.suiteId(), suite.name(), suite.status(), suite.caseCount(),
            suite.confirmedCaseCount(), suite.verifiedArtifactCaseCount(),
            suite.contentHash(), suite.createdBy(),
            suite.createdAt(), repository.findRuns(suite.suiteId()).stream().findFirst().orElse(null)
        )).toList();
    }

    public SuiteDetail get(UUID suiteId) {
        Suite suite = requireSuite(suiteId);
        try {
            return new SuiteDetail(suite, objectMapper.readTree(suite.manifestJson()),
                repository.findRuns(suiteId));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored golden manifest is invalid", exception);
        }
    }

    public Run evaluate(UUID suiteId, List<CaseEvaluation> results, String operatorId) {
        Suite suite = requireSuite(suiteId);
        requireText(operatorId, "operator id");
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("at least one case evaluation is required");
        }
        Set<String> expectedIds = caseIds(suite.manifestJson());
        Set<String> resultIds = new HashSet<>();
        int subjectMismatch = 0;
        int majorRisks = 0;
        int supportedMajorRisks = 0;
        int explainable = 0;
        int docxPass = 0;
        int critical = 0;
        int high = 0;
        BigDecimal totalMinutes = BigDecimal.ZERO;
        int timed = 0;
        for (CaseEvaluation result : results) {
            if (result == null || !expectedIds.contains(result.caseId())
                || !resultIds.add(result.caseId())) {
                throw new IllegalArgumentException("evaluation case id is missing, duplicate or unknown");
            }
            if (!result.subjectMatched()) subjectMismatch++;
            if (result.majorRiskCount() < 0 || result.supportedMajorRiskCount() < 0
                || result.supportedMajorRiskCount() > result.majorRiskCount()
                || result.criticalDefectCount() < 0 || result.highDefectCount() < 0) {
                throw new IllegalArgumentException("evaluation counts are invalid");
            }
            majorRisks += result.majorRiskCount();
            supportedMajorRisks += result.supportedMajorRiskCount();
            if (result.scoreExplainable()) explainable++;
            if (result.docxCoreFieldsOk()) docxPass++;
            critical += result.criticalDefectCount();
            high += result.highDefectCount();
            if (result.manualMinutes() != null) {
                if (result.manualMinutes().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("manual minutes cannot be negative");
                }
                totalMinutes = totalMinutes.add(result.manualMinutes()); timed++;
            }
        }
        boolean complete = resultIds.equals(expectedIds);
        boolean passed = complete && "READY".equals(suite.status())
            && subjectMismatch == 0 && majorRisks == supportedMajorRisks
            && explainable == suite.caseCount() && docxPass == suite.caseCount()
            && critical == 0 && high == 0;
        BigDecimal average = timed == 0 ? null
            : totalMinutes.divide(BigDecimal.valueOf(timed), 2, RoundingMode.HALF_UP);
        String json = canonical(objectMapper.valueToTree(results));
        return repository.saveRun(new Run(
            UUID.randomUUID(), suiteId, passed ? "PASSED" : complete ? "FAILED" : "INCOMPLETE",
            suite.caseCount(), results.size(), subjectMismatch, majorRisks,
            supportedMajorRisks, explainable, docxPass, critical, high,
            average, json, operatorId.trim(), clock.instant()
        ));
    }

    private void validateCase(JsonNode sample, Set<String> ids) {
        String id = text(sample, "id");
        if (!id.matches("[a-z0-9][a-z0-9-]{2,63}") || !ids.add(id)) {
            throw new IllegalArgumentException("case id is invalid or duplicated: " + id);
        }
        JsonNode company = object(sample, "company");
        requireText(text(company, "canonical_name"), "company canonical name");
        String creditCode = text(company, "unified_credit_code");
        if (creditCode.length() != 18) {
            throw new IllegalArgumentException("unified credit code must contain 18 characters");
        }
        JsonNode identityTerms = company.path("identity_terms");
        if (!identityTerms.isArray() || identityTerms.isEmpty()) {
            throw new IllegalArgumentException("each case needs enterprise name, alias or brand identity terms");
        }
        JsonNode artifacts = object(sample, "artifacts");
        for (String key : List.of("previous_report", "final_report", "company_json", "operator_decisions")) {
            requireSafeRelativePath(text(artifacts, key), key);
        }
        JsonNode expected = object(sample, "expected");
        score(expected, "original_score"); score(expected, "manual_score");
        if (!Set.of("LOW", "MEDIUM_LOW", "MEDIUM", "MEDIUM_HIGH", "HIGH")
            .contains(text(expected, "risk_level"))) {
            throw new IllegalArgumentException("expected risk level is invalid");
        }
        JsonNode evidence = sample.path("evidence_labels");
        if (!evidence.isArray()) {
            throw new IllegalArgumentException("evidence_labels must be an array");
        }
        for (JsonNode label : evidence) {
            requireText(text(label, "risk_type"), "evidence risk type");
            requireText(text(label, "matched_identity_term"), "matched identity term");
            if (label.path("major_risk").asBoolean(false)) {
                if (!label.path("include_in_report").asBoolean(false)
                    || !label.path("entity_match_expected").asBoolean(false)) {
                    throw new IllegalArgumentException(
                        "major risk evidence must belong to the enterprise and enter the report"
                    );
                }
                requireText(text(label, "source_url"), "major risk source url");
            }
        }
    }

    private Suite requireSuite(UUID suiteId) {
        return repository.findSuite(suiteId)
            .orElseThrow(() -> new IllegalArgumentException("golden suite not found"));
    }

    private Set<String> caseIds(String manifestJson) {
        try {
            Set<String> ids = new HashSet<>();
            objectMapper.readTree(manifestJson).path("cases")
                .forEach(sample -> ids.add(sample.path("id").asText()));
            return ids;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored golden manifest is invalid", exception);
        }
    }

    private String canonical(JsonNode node) {
        try { return objectMapper.writeValueAsString(node); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("golden data cannot be serialized", exception);
        }
    }

    private static JsonNode object(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isObject()) throw new IllegalArgumentException(key + " must be an object");
        return value;
    }

    private static String text(JsonNode node, String key) {
        String value = node.path(key).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static void score(JsonNode node, String key) {
        BigDecimal value;
        try { value = new BigDecimal(text(node, key)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " is invalid"); }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException(key + " must be in [0,10]");
        }
    }

    private static void requireSafeRelativePath(String value, String key) {
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("..")
            || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(key + " must be a safe relative path");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record SuiteSummary(
        UUID suiteId, String name, String status, int caseCount,
        int confirmedCaseCount, int verifiedArtifactCaseCount,
        String contentHash, String createdBy,
        java.time.Instant createdAt, Run latestRun
    ) {
    }

    public record SuiteDetail(Suite suite, JsonNode manifest, List<Run> runs) {
    }

    public record CaseEvaluation(
        String caseId, boolean subjectMatched, int majorRiskCount,
        int supportedMajorRiskCount, boolean scoreExplainable,
        boolean docxCoreFieldsOk, int criticalDefectCount,
        int highDefectCount, BigDecimal manualMinutes, String notes
    ) {
    }
}
