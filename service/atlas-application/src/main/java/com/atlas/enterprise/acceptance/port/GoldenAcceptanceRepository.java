package com.atlas.enterprise.acceptance.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoldenAcceptanceRepository {
    Suite saveSuite(Suite suite);
    List<Suite> findSuites();
    Optional<Suite> findSuite(UUID suiteId);
    Run saveRun(Run run);
    List<Run> findRuns(UUID suiteId);

    record Suite(
        UUID suiteId, String name, String schemaVersion, String status,
        int caseCount, int confirmedCaseCount, int verifiedArtifactCaseCount, String manifestJson,
        String contentHash, String createdBy, Instant createdAt
    ) {
    }

    record Run(
        UUID runId, UUID suiteId, String status, int caseCount,
        int completedCaseCount, int severeSubjectMismatchCount,
        int majorRiskCount, int supportedMajorRiskCount,
        int explainableScoreCount, int docxPassCount,
        int criticalDefectCount, int highDefectCount,
        BigDecimal averageManualMinutes, String resultJson,
        String operatorId, Instant createdAt
    ) {
    }
}
