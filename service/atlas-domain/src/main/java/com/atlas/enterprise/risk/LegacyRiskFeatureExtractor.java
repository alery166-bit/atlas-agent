package com.atlas.enterprise.risk;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Builds a deterministic legacy feature set from one frozen data snapshot. */
public final class LegacyRiskFeatureExtractor {
    private static final long ONE_YEAR_SECONDS = 365L * 24 * 60 * 60;
    private static final long HALF_YEAR_SECONDS = 180L * 24 * 60 * 60;

    private LegacyRiskFeatureExtractor() {
    }

    public static LegacyRiskFeatures extract(DataSnapshot snapshot, Instant calculatedAt) {
        return extract(snapshot, calculatedAt, RiskScoringPolicy.defaultPolicy());
    }

    public static LegacyRiskFeatures extract(
        DataSnapshot snapshot,
        Instant calculatedAt,
        RiskScoringPolicy policy
    ) {
        if (snapshot == null || calculatedAt == null) {
            throw new IllegalArgumentException("snapshot and calculatedAt are required");
        }
        Map<String, String> fields = snapshot.companyFacts().additionalFields();
        RiskScoringPolicy effectivePolicy = policy == null
            ? RiskScoringPolicy.defaultPolicy()
            : policy;
        Instant oneYearAgo = calculatedAt.minusSeconds(
            effectivePolicy.riskEventWindowDays() * 24L * 60 * 60
        );
        Instant halfYearAgo = calculatedAt.minusSeconds(
            effectivePolicy.companyChangeWindowDays() * 24L * 60 * 60
        );

        int judicial = 0;
        int judicialKeyword = integer(fields, "legacyJudicialKeywordCount");
        int abnormal = 0;
        int seriousIllegal = 0;
        int penalty = 0;
        int equityPledge = 0;
        int equityFreeze = 0;
        int stockPledge = 0;
        int sentiment = integer(fields, "legacySentimentCount");
        int sentimentKeyword = integer(fields, "legacySentimentKeywordCount");
        int complaint = integer(fields, "legacyComplaintCount");
        int complaintKeyword = integer(fields, "legacyComplaintKeywordCount");

        for (RiskEvent event : snapshot.riskEvents()) {
            RiskType type = RiskType.fromCanonicalName(event.eventType());
            Instant occurredAt = reliableOccurredAt(event, type);
            if (occurredAt == null || occurredAt.isBefore(oneYearAgo)
                || !occurredAt.isBefore(calculatedAt)) {
                continue;
            }
            switch (type) {
                case JUDICIAL_DOCUMENT, JUDGMENT_DEBTOR -> judicial++;
                case BUSINESS_ABNORMAL -> abnormal++;
                case SERIOUS_ILLEGAL -> seriousIllegal++;
                case ADMINISTRATIVE_PENALTY -> penalty++;
                case EQUITY_PLEDGE -> equityPledge++;
                case EQUITY_FREEZE -> equityFreeze++;
                case STOCK_PLEDGE -> stockPledge++;
                case OFFLINE_COMPLAINT -> complaint++;
                default -> {
                    String eventType = normalize(event.eventType());
                    if (eventType.contains("COMPLAINT")) {
                        complaint++;
                    }
                    if (eventType.contains("NEGATIVE_SENTIMENT")) {
                        sentiment++;
                    }
                }
            }
        }

        boolean keyPartyChanged = false;
        boolean addressChanged = false;
        for (CompanyChange change : snapshot.companyChanges()) {
            Instant changedAt = parseInstant(change.changedAt());
            if (changedAt == null || changedAt.isBefore(halfYearAgo)
                || !changedAt.isBefore(calculatedAt)) {
                continue;
            }
            String item = change.changeItem() == null ? "" : change.changeItem();
            if (containsAny(item, "法定代表人", "负责人", "法人", "股东", "投资人", "投资者")) {
                keyPartyChanged = true;
                addressChanged = true;
            } else if (item.contains("住所") || item.contains("地址")) {
                addressChanged = true;
            }
        }

        Set<String> normalizedLabels = normalizedLegacyLabels(
            first(fields, "legacyLabels", "riskLabels", "riskLabel"),
            snapshot.companyFacts().registrationStatus()
        );

        return new LegacyRiskFeatures(
            complete(fields),
            LegacyScoringProfile.from(fields.get("legacyScoringProfile")),
            sentiment,
            sentimentKeyword,
            complaint,
            complaintKeyword,
            judicial,
            judicialKeyword,
            abnormal,
            seriousIllegal,
            penalty,
            equityPledge,
            equityFreeze,
            stockPledge,
            longSet(first(fields, "legacyIndustryIds", "industryIds", "industryId")),
            normalizedLabels,
            keyPartyChanged,
            addressChanged,
            decimal(first(fields, "paidCapital", "payedCapital")),
            operatingYears(snapshot.companyFacts().establishedDate(), calculatedAt),
            first(fields, "listingInfo", "listing") == null
                ? ""
                : first(fields, "listingInfo", "listing"),
            bool(first(fields, "monitorCompany", "isMonitor")),
            decimal(first(fields, "relatedShareholderScore")),
            decimal(first(fields, "relatedInvestmentScore"))
        );
    }

    private static Instant reliableOccurredAt(RiskEvent event, RiskType type) {
        Instant occurredAt = parseInstant(event.occurredAt());
        if (occurredAt == null) {
            return null;
        }
        if (type == RiskType.EQUITY_FREEZE
            && event.dataAsOf() != null
            && occurredAt.equals(event.dataAsOf())) {
            return yearFromDocumentNo(event.rawFields().get("documentNo"));
        }
        return occurredAt;
    }

    private static Instant yearFromDocumentNo(String documentNo) {
        if (documentNo == null || documentNo.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?:（|\\()?(20\\d{2})(?:）|\\))?")
            .matcher(documentNo);
        if (!matcher.find()) {
            return null;
        }
        return LocalDate.of(Integer.parseInt(matcher.group(1)), 1, 1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    }

    private static boolean complete(Map<String, String> fields) {
        String value = first(fields, "legacyFeatureCompleteness", "legacyFeaturesComplete");
        return "FULL".equalsIgnoreCase(value) || bool(value);
    }

    private static int integer(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        String normalized = value.replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank() || "-".equals(normalized)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static Integer operatingYears(String value, Instant calculatedAt) {
        Instant openedAt = parseInstant(value);
        if (openedAt == null) {
            return null;
        }
        int years = Period.between(
            openedAt.atZone(ZoneOffset.UTC).toLocalDate(),
            calculatedAt.atZone(ZoneOffset.UTC).toLocalDate()
        ).getYears() + 1;
        return Math.max(0, years);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            long epoch = Long.parseLong(normalized);
            return epoch > 10_000_000_000L
                ? Instant.ofEpochMilli(epoch)
                : Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException ignored) {
            // Try standard temporal formats below.
        }
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(normalized).toInstant();
            } catch (DateTimeParseException offsetIgnored) {
                try {
                    return LocalDate.parse(normalized).atStartOfDay().toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException alsoIgnored) {
                    return null;
                }
            }
        }
    }

    private static Set<Long> longSet(String value) {
        Set<Long> result = new LinkedHashSet<>();
        for (String item : tokens(value)) {
            try {
                result.add(Long.parseLong(item));
            } catch (NumberFormatException ignored) {
                // Invalid legacy identifiers are ignored and remain visible in the snapshot.
            }
        }
        return result;
    }

    private static Set<String> stringSet(String value) {
        return new LinkedHashSet<>(tokens(value));
    }

    private static Set<String> normalizedLegacyLabels(
        String value,
        String registrationStatus
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : stringSet(value)) {
            String normalized = normalizeLegacyLabel(token);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        String status = registrationStatus == null ? "" : registrationStatus.trim();
        if (status.contains("吊销")) {
            result.add(RiskType.REVOKED.legacyLabelNo());
        } else if (status.contains("注销")) {
            result.add(RiskType.CANCELLATION.legacyLabelNo());
        }
        return result;
    }

    private static String normalizeLegacyLabel(String value) {
        String token = value == null ? "" : value.trim();
        if (token.isBlank()) {
            return null;
        }
        for (RiskType type : RiskType.values()) {
            if (type.legacyLabelNo() == null) {
                continue;
            }
            if (type.legacyLabelNo().equals(token)
                || type.displayName().equals(token)
                || type.name().equalsIgnoreCase(token)) {
                return type.legacyLabelNo();
            }
        }
        RiskType canonical = RiskType.fromCanonicalName(token);
        return canonical != RiskType.OTHER && canonical.legacyLabelNo() != null
            ? canonical.legacyLabelNo()
            : token;
    }

    private static java.util.List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return java.util.List.of();
        }
        return Arrays.stream(value.replaceAll("[\\[\\]\\\"]", "").split("[,;\\s]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private static String first(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean bool(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static boolean containsAny(String value, String... needles) {
        return Arrays.stream(needles).anyMatch(value::contains);
    }
}
