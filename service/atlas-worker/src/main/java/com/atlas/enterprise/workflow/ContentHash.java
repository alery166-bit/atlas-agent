package com.atlas.enterprise.workflow;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class ContentHash {
    private ContentHash() {
    }

    static String snapshot(
        CompanyFacts facts,
        List<CompanyChange> changes,
        List<RiskEvent> riskEvents,
        List<SourceStatus> sourceStatuses
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, facts.canonicalName());
        append(canonical, facts.unifiedCreditCode());
        append(canonical, facts.registrationNo());
        append(canonical, facts.legalRepresentative());
        append(canonical, facts.registrationStatus());
        append(canonical, facts.registeredAddress());
        append(canonical, facts.businessScope());
        appendMap(canonical, facts.additionalFields());

        changes.stream()
            .sorted(Comparator.comparing(CompanyChange::sourceRecordId, Comparator.nullsFirst(String::compareTo)))
            .forEach(change -> {
                append(canonical, change.sourceRecordId());
                append(canonical, change.changeItem());
                append(canonical, change.changedAt());
                append(canonical, change.beforeValue());
                append(canonical, change.afterValue());
                appendMap(canonical, change.rawFields());
            });

        riskEvents.stream()
            .sorted(Comparator.comparing(RiskEvent::sourceName)
                .thenComparing(RiskEvent::sourceRecordId, Comparator.nullsFirst(String::compareTo)))
            .forEach(event -> {
                append(canonical, event.eventType());
                append(canonical, event.sourceName());
                append(canonical, event.sourceRecordId());
                append(canonical, event.occurredAt());
                append(canonical, event.summary());
                appendMap(canonical, event.rawFields());
            });

        sourceStatuses.stream()
            .sorted(Comparator.comparing(SourceStatus::sourceName))
            .forEach(status -> {
                append(canonical, status.sourceSystem());
                append(canonical, status.sourceName());
                append(canonical, status.queryStatus().name());
                append(canonical, Long.toString(status.recordCount()));
                append(canonical, status.dataAsOf() == null ? null : status.dataAsOf().toString());
            });
        return sha256(canonical.toString());
    }

    static String value(String value) {
        return sha256(value == null ? "" : value);
    }

    private static void appendMap(StringBuilder canonical, Map<String, String> values) {
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                append(canonical, entry.getKey());
                append(canonical, entry.getValue());
            });
    }

    private static void append(StringBuilder canonical, String value) {
        String safe = value == null ? "" : value;
        canonical.append(safe.length()).append(':').append(safe).append('|');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
