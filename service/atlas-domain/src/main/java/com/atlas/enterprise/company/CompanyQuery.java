package com.atlas.enterprise.company;

public record CompanyQuery(String value) {
    public CompanyQuery {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Company query must not be blank");
        }
        value = value.trim();
    }

    public boolean looksLikeUnifiedCreditCode() {
        return value.matches("[0-9A-Z]{18}");
    }
}
