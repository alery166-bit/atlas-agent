package com.atlas.enterprise.company;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DataSnapshot(
    UUID snapshotId,
    UUID taskId,
    UUID atlasCompanyId,
    int snapshotVersion,
    CompanyFacts companyFacts,
    List<CompanyChange> companyChanges,
    List<RiskEvent> riskEvents,
    List<SourceStatus> sourceStatuses,
    String contentHash,
    Instant frozenAt
) {
    public DataSnapshot {
        companyChanges = companyChanges == null ? List.of() : List.copyOf(companyChanges);
        riskEvents = riskEvents == null ? List.of() : List.copyOf(riskEvents);
        sourceStatuses = sourceStatuses == null ? List.of() : List.copyOf(sourceStatuses);
    }
}
