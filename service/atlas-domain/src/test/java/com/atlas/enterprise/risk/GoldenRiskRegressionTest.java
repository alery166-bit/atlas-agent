package com.atlas.enterprise.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.CompanyFacts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class GoldenRiskRegressionTest {
    private static final String SCHEMA_VERSION = "atlas-golden.v1";
    private static final String MANIFEST_PROPERTY = "atlas.golden.manifest";
    private static final String MANIFEST_ENVIRONMENT =
        "ATLAS_GOLDEN_MANIFEST";

    private final RiskScoreEngine engine = new VersionedRiskScoreEngine();

    @TestFactory
    Stream<DynamicTest> goldenRiskCases() throws IOException {
        Path manifestPath = locateManifest();
        GoldenSuite suite = objectMapper().readValue(
            manifestPath.toFile(),
            GoldenSuite.class
        );
        validateSuite(suite, manifestPath);
        return suite.cases().stream().map(goldenCase ->
            DynamicTest.dynamicTest(
                goldenCase.id(),
                () -> verify(goldenCase)
            )
        );
    }

    private void verify(GoldenCase goldenCase) {
        RiskScoreRequest request = request(goldenCase);
        RiskScoreResult result = engine.calculate(request);
        RiskScoreResult repeated = engine.calculate(new RiskScoreRequest(
            request.taskId(),
            request.dataSnapshotId(),
            request.companyFacts(),
            request.confirmedRiskEvents(),
            request.legacyScore(),
            request.ruleVersion(),
            request.calculatedAt().plusSeconds(30)
        ));

        assertDecimal(
            goldenCase.expected().ruleCalculatedScore(),
            result.ruleCalculatedScore()
        );
        assertDecimal(
            goldenCase.expected().eventFloorScore(),
            result.eventFloorScore()
        );
        assertDecimal(
            goldenCase.expected().originalScore(),
            result.originalScore()
        );
        assertDecimal(
            goldenCase.expected().manualScore(),
            result.manualScore()
        );
        assertEquals(
            RiskLevel.valueOf(goldenCase.expected().riskLevel()),
            result.originalRiskLevel()
        );
        assertEquals(
            goldenCase.expected().ruleHitCount(),
            result.ruleHits().size()
        );
        Set<String> actualRuleCodes = result.ruleHits().stream()
            .map(RiskRuleHit::ruleCode)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(
            actualRuleCodes.containsAll(
                goldenCase.expected().requiredRuleCodes()
            ),
            () -> "Missing expected rule codes. Actual: " + actualRuleCodes
        );
        assertEquals(result.inputHash(), repeated.inputHash());
        assertEquals(
            VersionedRiskScoreEngine.RULE_VERSION,
            result.ruleVersion()
        );
        assertEquals(
            VersionedRiskScoreEngine.ENGINE_VERSION,
            result.engineVersion()
        );
    }

    private static RiskScoreRequest request(GoldenCase goldenCase) {
        UUID taskId = deterministicUuid(goldenCase.id() + ":task");
        UUID dataSnapshotId = deterministicUuid(
            goldenCase.id() + ":snapshot"
        );
        List<ConfirmedRiskEvent> events = goldenCase.confirmedEvents().stream()
            .map(event -> new ConfirmedRiskEvent(
                RiskType.fromCanonicalName(event.riskType()),
                event.referenceId(),
                event.title(),
                event.evidenceIds()
            ))
            .toList();
        return new RiskScoreRequest(
            taskId,
            dataSnapshotId,
            companyFacts(goldenCase),
            events,
            decimal(goldenCase.legacyScore()),
            VersionedRiskScoreEngine.RULE_VERSION,
            Instant.parse("2026-07-30T00:00:00Z")
        );
    }

    private static CompanyFacts companyFacts(GoldenCase goldenCase) {
        return new CompanyFacts(
            goldenCase.companyName(),
            goldenCase.unifiedCreditCode(),
            "golden-registration",
            "黄金样本法人",
            "存续",
            "黄金样本地址",
            "有限责任公司",
            "100 万元",
            "2020-01-01",
            "黄金样本登记机关",
            "黄金样本经营范围",
            "黄金样本行业",
            "GOLDEN",
            goldenCase.id(),
            Instant.parse("2026-07-29T00:00:00Z"),
            Instant.parse("2026-07-30T00:00:00Z"),
            Map.of()
        );
    }

    private static void validateSuite(
        GoldenSuite suite,
        Path manifestPath
    ) {
        assertEquals(SCHEMA_VERSION, suite.schemaVersion());
        assertFalse(suite.cases().isEmpty(), "Golden suite must contain cases");
        Set<String> ids = new HashSet<>();
        for (GoldenCase goldenCase : suite.cases()) {
            assertTrue(
                ids.add(goldenCase.id()),
                "Duplicate golden case id: " + goldenCase.id()
            );
            validateSources(goldenCase.source(), manifestPath.getParent());
        }
    }

    private static void validateSources(
        GoldenSource source,
        Path manifestDirectory
    ) {
        if (source == null) {
            return;
        }
        for (String reference : List.of(
            nullToEmpty(source.previousReport()),
            nullToEmpty(source.finalReport()),
            nullToEmpty(source.companyJson()),
            nullToEmpty(source.operatorDecisions())
        )) {
            if (!reference.isBlank()) {
                assertTrue(
                    Files.isRegularFile(
                        manifestDirectory.resolve(reference).normalize()
                    ),
                    "Referenced golden source does not exist: " + reference
                );
            }
        }
    }

    private static Path locateManifest() {
        String explicit = System.getProperty(MANIFEST_PROPERTY);
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv(MANIFEST_ENVIRONMENT);
        }
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit).toAbsolutePath().normalize();
            assertTrue(
                Files.isRegularFile(path),
                "Golden manifest does not exist: " + path
            );
            return path;
        }
        List<Path> candidates = List.of(
            Path.of("data", "golden", "seed-risk-cases.json"),
            Path.of("..", "data", "golden", "seed-risk-cases.json"),
            Path.of("..", "..", "data", "golden", "seed-risk-cases.json")
        );
        return candidates.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Golden manifest was not found. Set -D"
                    + MANIFEST_PROPERTY + "=<path>."
            ));
    }

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(
            PropertyNamingStrategies.SNAKE_CASE
        );
        return mapper;
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertDecimal(
        String expected,
        BigDecimal actual
    ) {
        assertEquals(
            0,
            new BigDecimal(expected).compareTo(actual),
            () -> "Expected " + expected + " but was " + actual
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record GoldenSuite(String schemaVersion, List<GoldenCase> cases) {
        GoldenSuite {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    record GoldenCase(
        String id,
        String companyName,
        String unifiedCreditCode,
        String legacyScore,
        List<GoldenEvent> confirmedEvents,
        GoldenExpected expected,
        GoldenSource source
    ) {
        GoldenCase {
            confirmedEvents = confirmedEvents == null
                ? List.of()
                : List.copyOf(confirmedEvents);
        }
    }

    record GoldenEvent(
        String riskType,
        String referenceId,
        String title,
        List<String> evidenceIds
    ) {
        GoldenEvent {
            evidenceIds = evidenceIds == null
                ? List.of()
                : List.copyOf(evidenceIds);
        }
    }

    record GoldenExpected(
        String ruleCalculatedScore,
        String eventFloorScore,
        String originalScore,
        String manualScore,
        String riskLevel,
        int ruleHitCount,
        List<String> requiredRuleCodes,
        List<String> requiredReportText
    ) {
        GoldenExpected {
            requiredRuleCodes = requiredRuleCodes == null
                ? List.of()
                : List.copyOf(requiredRuleCodes);
            requiredReportText = requiredReportText == null
                ? List.of()
                : List.copyOf(requiredReportText);
        }
    }

    record GoldenSource(
        String previousReport,
        String finalReport,
        String companyJson,
        String operatorDecisions
    ) {
    }
}
