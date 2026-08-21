package com.atlas.enterprise.risk;

/**
 * Explicit legacy regional scoring profile.
 *
 * <p>Profiles are selected by configuration/feature materialization. The
 * scoring engine never infers a regional exception from an address.</p>
 */
public enum LegacyScoringProfile {
    STANDARD,
    CHAOYANG,
    XIAN;

    public static LegacyScoringProfile from(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return STANDARD;
        }
    }
}
