package com.atlas.enterprise;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void parsesAndContainsPublicIntelligenceContract() throws IOException {
        Path contract = Path.of("..", "openapi", "openapi-v1.yaml");
        try (InputStream input = Files.newInputStream(contract)) {
            Map<String, Object> document = new Yaml().load(input);
            Map<String, Object> paths = (Map<String, Object>) document.get("paths");
            Map<String, Object> components =
                (Map<String, Object>) document.get("components");
            Map<String, Object> schemas =
                (Map<String, Object>) components.get("schemas");

            assertThat(document.get("openapi")).isEqualTo("3.1.0");
            assertThat(paths).containsKeys(
                "/api/tasks/{taskId}/public-intelligence/search",
                "/api/tasks/{taskId}/public-intelligence/searches",
                "/api/tasks/{taskId}/public-intelligence/evidence",
                "/api/tasks/{taskId}/public-intelligence/evidence/model-review",
                "/api/tasks/{taskId}/public-intelligence/evidence/model-review/jobs/latest",
                "/api/tasks/{taskId}/public-intelligence/evidence/model-review/jobs/{reviewJobId}/cancel",
                "/api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision",
                "/api/tasks/{taskId}/risk-score/calculate-from-confirmed-evidence",
                "/api/tasks/{taskId}/risk-score/history",
                "/api/tasks/{taskId}/operator-confirmation",
                "/api/tasks/{taskId}/operator-confirmations",
                "/api/tasks/{taskId}/workspace",
                "/api/platform/operations",
                "/api/platform/audit",
                "/api/platform/audit/export",
                "/api/platform/configuration-changes",
                "/api/platform/risk-rules/traceability",
                "/api/platform/golden-acceptance/suites",
                "/api/platform/golden-acceptance/suites/{suiteId}",
                "/api/platform/golden-acceptance/suites/{suiteId}/evaluations"
            );
            assertThat(schemas).containsKeys(
                "PublicIntelligenceRun",
                "SearchExecution",
                "PublicEvidence",
                "EvidenceSemanticReviewRun",
                "EvidenceSemanticReviewJob",
                "EvidenceSemanticSuggestion",
                "EvidenceDecision",
                "OperatorConfirmationRequest",
                "OperatorConfirmation",
                "TaskWorkspace",
                "EvidenceProgress",
                "PreviousReportScore",
                "LegacyRiskTraceability",
                "RiskAssessmentLabel",
                "RiskAssessmentRevision"
            );
        }
    }
}
