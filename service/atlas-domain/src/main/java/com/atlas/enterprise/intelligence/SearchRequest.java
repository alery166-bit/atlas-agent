package com.atlas.enterprise.intelligence;

import com.atlas.enterprise.risk.RiskType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SearchRequest(
    UUID taskId,
    UUID atlasCompanyId,
    String companyName,
    String unifiedCreditCode,
    String query,
    RiskType targetRisk,
    String sourceScope,
    List<String> includeDomains,
    boolean includeRawContent,
    String topic,
    Instant requestedAt
) {
    public SearchRequest {
        if (taskId == null || atlasCompanyId == null) {
            throw new IllegalArgumentException("taskId and atlasCompanyId are required");
        }
        if (companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("companyName must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        targetRisk = targetRisk == null ? RiskType.OTHER : targetRisk;
        sourceScope = sourceScope == null || sourceScope.isBlank()
            ? "GENERAL_WEB"
            : sourceScope.trim();
        includeDomains = includeDomains == null ? List.of() : List.copyOf(includeDomains);
        topic = topic == null || topic.isBlank() ? "general" : topic.trim();
        requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
    }

    public SearchRequest(
        UUID taskId,
        UUID atlasCompanyId,
        String companyName,
        String unifiedCreditCode,
        String query,
        RiskType targetRisk,
        Instant requestedAt
    ) {
        this(
            taskId, atlasCompanyId, companyName, unifiedCreditCode, query,
            targetRisk, "GENERAL_WEB", List.of(), false, "general", requestedAt
        );
    }
}
