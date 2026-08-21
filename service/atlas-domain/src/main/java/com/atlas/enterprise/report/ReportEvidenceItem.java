package com.atlas.enterprise.report;

import com.atlas.enterprise.risk.RiskType;
import java.time.Instant;
import java.util.UUID;

public record ReportEvidenceItem(
    UUID evidenceId,
    RiskType riskType,
    String title,
    String excerpt,
    String sourceProvider,
    String sourceUrl,
    String sourceDomain,
    Instant publishedAt,
    Instant capturedAt,
    UUID contentSnapshotId,
    String contentHash,
    boolean contentTruncated
) {
    public ReportEvidenceItem {
        if (evidenceId == null) {
            throw new IllegalArgumentException("evidenceId is required");
        }
        riskType = riskType == null ? RiskType.OTHER : riskType;
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        excerpt = excerpt == null ? "" : excerpt.trim();
        if (sourceProvider == null || sourceProvider.isBlank()) {
            throw new IllegalArgumentException("sourceProvider is required");
        }
        sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        sourceDomain = sourceDomain == null ? "" : sourceDomain.trim();
        if (capturedAt == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("capturedAt and contentHash are required");
        }
    }
}
