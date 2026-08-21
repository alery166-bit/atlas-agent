package com.atlas.enterprise.company;

import java.time.Instant;
import java.util.UUID;

public record CompanyAlias(
    UUID aliasId,
    UUID atlasCompanyId,
    String aliasName,
    CompanyAliasType aliasType,
    CompanyAliasRelation relation,
    CompanyAliasVerificationStatus verificationStatus,
    String sourceSystem,
    String sourceRecordId,
    String sourceEvidence,
    String createdBy,
    Instant validFrom,
    Instant validTo,
    Instant createdAt,
    Instant updatedAt
) {
    public CompanyAlias {
        if (aliasId == null || atlasCompanyId == null) {
            throw new IllegalArgumentException("Company alias identifiers are required");
        }
        if (aliasName == null || aliasName.isBlank() || aliasName.trim().length() > 256) {
            throw new IllegalArgumentException("Company alias name must contain 1 to 256 characters");
        }
        aliasName = aliasName.trim();
        if (aliasType == null || relation == null || verificationStatus == null) {
            throw new IllegalArgumentException("Alias type, relation and verification status are required");
        }
        if (sourceSystem == null || sourceSystem.isBlank()) {
            throw new IllegalArgumentException("Alias source system is required");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Alias timestamps are required");
        }
    }
}
