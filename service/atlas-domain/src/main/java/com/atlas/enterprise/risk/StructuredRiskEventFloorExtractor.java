package com.atlas.enterprise.risk;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Converts recent structured source records into deterministic event-floor inputs. */
public final class StructuredRiskEventFloorExtractor {
    private StructuredRiskEventFloorExtractor() {
    }

    public static List<ConfirmedRiskEvent> extract(
        DataSnapshot snapshot,
        Instant calculatedAt,
        RiskScoringPolicy policy
    ) {
        Instant windowStart = calculatedAt.minusSeconds(
            policy.riskEventWindowDays() * 24L * 60 * 60
        );
        return snapshot.riskEvents().stream()
            .filter(event -> eligible(event, calculatedAt, windowStart, policy))
            .map(event -> confirmed(event))
            .toList();
    }

    private static boolean eligible(
        RiskEvent event,
        Instant calculatedAt,
        Instant windowStart,
        RiskScoringPolicy policy
    ) {
        RiskType type = RiskType.fromCanonicalName(event.eventType());
        if (policy.floorFor(type).signum() <= 0
            || event.sourceRecordId() == null
            || event.sourceRecordId().isBlank()) {
            return false;
        }
        Instant occurredAt = parseInstant(event.occurredAt());
        return occurredAt != null
            && !occurredAt.isBefore(windowStart)
            && !occurredAt.isAfter(calculatedAt);
    }

    private static ConfirmedRiskEvent confirmed(RiskEvent event) {
        RiskType type = RiskType.fromCanonicalName(event.eventType());
        String source = event.sourceSystem() == null || event.sourceSystem().isBlank()
            ? "STRUCTURED"
            : event.sourceSystem().trim();
        String reference = "structured-risk:" + source + ":" + event.sourceRecordId().trim();
        String title = event.title() == null || event.title().isBlank()
            ? type.displayName()
            : event.title().trim();
        return new ConfirmedRiskEvent(type, reference, title, List.of());
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
                    return LocalDate.parse(normalized)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException alsoIgnored) {
                    return null;
                }
            }
        }
    }
}
