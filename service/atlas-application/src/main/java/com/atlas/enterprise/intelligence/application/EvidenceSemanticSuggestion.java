package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.risk.RiskType;
import java.util.UUID;

public record EvidenceSemanticSuggestion(
    UUID evidenceId,
    Relevance relevance,
    RiskType riskType,
    double confidence,
    String reason,
    String summary
) {
    public EvidenceSemanticSuggestion {
        if (evidenceId == null || relevance == null || riskType == null
            || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("Invalid semantic suggestion");
        }
        reason = normalize(reason, 500);
        summary = normalize(summary, 1000);
    }

    private static String normalize(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= maximum
            ? normalized
            : normalized.substring(0, maximum);
    }

    public enum Relevance {
        RELEVANT,
        IRRELEVANT,
        UNCERTAIN
    }
}
