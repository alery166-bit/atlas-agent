package com.atlas.enterprise.intelligence;

import com.atlas.enterprise.risk.RiskType;
import java.time.Instant;
import java.util.UUID;

public record SearchExecution(
    UUID searchBatchId,
    UUID taskId,
    UUID snapshotId,
    String provider,
    ProviderCapabilities.ProviderMode providerMode,
    String query,
    RiskType targetRisk,
    String sourceScope,
    SearchBatchStatus status,
    int resultCount,
    String failureCode,
    String failureMessage,
    Instant searchedAt
) {
    public SearchExecution {
        if (searchBatchId == null || taskId == null || snapshotId == null) {
            throw new IllegalArgumentException("search execution identifiers are required");
        }
        if (provider == null || provider.isBlank() || query == null || query.isBlank()) {
            throw new IllegalArgumentException("provider and query are required");
        }
        providerMode = providerMode == null
            ? ProviderCapabilities.ProviderMode.SEARCH_ENGINE
            : providerMode;
        targetRisk = targetRisk == null ? RiskType.OTHER : targetRisk;
        sourceScope = sourceScope == null || sourceScope.isBlank()
            ? "GENERAL_WEB"
            : sourceScope.trim();
        if (status == null || searchedAt == null || resultCount < 0) {
            throw new IllegalArgumentException("invalid search execution state");
        }
    }

    public SearchExecution(
        UUID searchBatchId,
        UUID taskId,
        UUID snapshotId,
        String provider,
        ProviderCapabilities.ProviderMode providerMode,
        String query,
        RiskType targetRisk,
        SearchBatchStatus status,
        int resultCount,
        String failureCode,
        String failureMessage,
        Instant searchedAt
    ) {
        this(
            searchBatchId, taskId, snapshotId, provider, providerMode, query,
            targetRisk, "GENERAL_WEB", status, resultCount, failureCode,
            failureMessage, searchedAt
        );
    }
}
