package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RiskRuleReplayRun(
    UUID replayId,
    UUID versionId,
    String versionChecksum,
    Status status,
    int sampleCount,
    int passedCount,
    int scoreChangedCount,
    int levelChangedCount,
    BigDecimal maxScoreDelta,
    String resultJson,
    String operatorId,
    Instant createdAt
) {
    public enum Status { PASSED, FAILED }

    public RiskRuleReplayRun {
        if (replayId == null || versionId == null || versionChecksum == null
            || versionChecksum.isBlank() || status == null || createdAt == null
            || sampleCount < 0 || passedCount < 0 || scoreChangedCount < 0
            || levelChangedCount < 0 || maxScoreDelta == null
            || resultJson == null || resultJson.isBlank()
            || operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("Invalid risk rule replay run");
        }
    }
}
