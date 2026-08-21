package com.atlas.enterprise.company.offline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class OfflineTimeParser {
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private OfflineTimeParser() {
    }

    static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.matches("\\d{13}")) {
                return Instant.ofEpochMilli(Long.parseLong(trimmed));
            }
            if (trimmed.matches("\\d{10}")) {
                return Instant.ofEpochSecond(Long.parseLong(trimmed));
            }
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed).atStartOfDay(SOURCE_ZONE).toInstant();
            }
            return LocalDateTime.parse(trimmed, DATE_TIME).atZone(SOURCE_ZONE).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static Instant later(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}
