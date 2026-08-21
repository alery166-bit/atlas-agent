package com.atlas.enterprise.company;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record CompanyFacts(
    String canonicalName,
    String unifiedCreditCode,
    String registrationNo,
    String legalRepresentative,
    String registrationStatus,
    String registeredAddress,
    String companyType,
    String registeredCapital,
    String establishedDate,
    String registrationAuthority,
    String businessScope,
    String industry,
    String sourceSystem,
    String sourceRecordId,
    Instant dataAsOf,
    Instant fetchedAt,
    Map<String, String> additionalFields
) {
    public CompanyFacts {
        additionalFields = additionalFields == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(additionalFields));
    }
}
