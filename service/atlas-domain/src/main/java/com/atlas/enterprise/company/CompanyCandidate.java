package com.atlas.enterprise.company;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record CompanyCandidate(
    String sourceSystem,
    String sourceEntityId,
    String canonicalName,
    String unifiedCreditCode,
    String registrationNo,
    String legalRepresentative,
    String registrationStatus,
    String registeredAddress,
    BigDecimal confidence,
    Instant dataAsOf,
    Map<String, String> attributes
) {
    public CompanyCandidate {
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public ResolvedCompany resolve() {
        return new ResolvedCompany(
            sourceSystem,
            sourceEntityId,
            canonicalName,
            unifiedCreditCode,
            registrationNo
        );
    }
}
