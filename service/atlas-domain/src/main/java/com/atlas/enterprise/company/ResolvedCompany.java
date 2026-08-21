package com.atlas.enterprise.company;

public record ResolvedCompany(
    String sourceSystem,
    String sourceEntityId,
    String canonicalName,
    String unifiedCreditCode,
    String registrationNo
) {
}
