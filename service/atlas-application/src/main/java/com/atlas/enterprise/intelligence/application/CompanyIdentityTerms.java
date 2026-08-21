package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.company.CompanyAliasVerificationStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;

/**
 * Builds the confirmed entity vocabulary used for public-intelligence search
 * and attribution. Values are taken only from structured company facts; this
 * class deliberately does not invent abbreviations from the legal name.
 */
public final class CompanyIdentityTerms {
    private static final int MAX_SEARCH_TERMS = 8;
    private static final List<FieldGroup> ALIAS_FIELDS = List.of(
        new FieldGroup("SHORT_NAME", List.of("shortName", "short_name", "shortNames")),
        new FieldGroup("FORMER_NAME", List.of("formerNames", "former_names", "aliases")),
        new FieldGroup("BRAND", List.of("brand", "brands", "brandNames", "brand_names")),
        new FieldGroup("STORE", List.of("store", "stores", "storeNames", "store_names", "branches")),
        new FieldGroup("WEBSITE", List.of("websiteName", "websiteNames", "website_names")),
        new FieldGroup("SOCIAL_ACCOUNT", List.of("socialName", "socialNames", "social_names"))
    );

    public List<IdentityTerm> confirmed(CompanyFacts company) {
        return confirmed(company, List.of());
    }

    public List<IdentityTerm> confirmed(
        CompanyFacts company,
        List<CompanyAlias> aliases
    ) {
        if (company == null) {
            return List.of();
        }
        Map<String, IdentityTerm> terms = new LinkedHashMap<>();
        add(terms, company.canonicalName(), "LEGAL_NAME");
        if (aliases != null) {
            aliases.stream()
                .filter(alias -> alias.verificationStatus()
                    == CompanyAliasVerificationStatus.CONFIRMED)
                .sorted(Comparator.comparing(
                    alias -> "OPERATOR".equals(alias.sourceSystem()) ? 0 : 1
                ))
                .forEach(alias -> add(
                    terms,
                    alias.aliasName(),
                    alias.aliasType().name()
                ));
        }
        Map<String, String> fields = company.additionalFields();
        for (FieldGroup group : ALIAS_FIELDS) {
            for (String key : group.keys()) {
                for (String value : split(fields.get(key))) {
                    add(terms, value, group.type());
                }
            }
        }
        add(terms, company.unifiedCreditCode(), "UNIFIED_CREDIT_CODE");
        return List.copyOf(terms.values());
    }

    public List<IdentityTerm> searchable(CompanyFacts company) {
        return searchable(company, List.of());
    }

    public List<IdentityTerm> searchable(
        CompanyFacts company,
        List<CompanyAlias> aliases
    ) {
        return confirmed(company, aliases).stream()
            .filter(term -> !"UNIFIED_CREDIT_CODE".equals(term.type()))
            .limit(MAX_SEARCH_TERMS)
            .toList();
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    private static void add(
        Map<String, IdentityTerm> terms,
        String value,
        String type
    ) {
        String cleaned = clean(value);
        String normalized = normalize(cleaned);
        if (normalized.length() < 2) {
            return;
        }
        terms.putIfAbsent(normalized, new IdentityTerm(cleaned, type));
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return List.of();
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,，;；|\\r\\n]+")) {
            String cleaned = clean(part);
            if (!cleaned.isBlank() && !"null".equalsIgnoreCase(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        while (cleaned.length() >= 2
            && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    public record IdentityTerm(String value, String type) {}

    private record FieldGroup(String type, List<String> keys) {}
}
