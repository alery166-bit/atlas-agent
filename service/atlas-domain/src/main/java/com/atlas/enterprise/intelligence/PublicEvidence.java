package com.atlas.enterprise.intelligence;

import com.atlas.enterprise.risk.RiskType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PublicEvidence(
    UUID evidenceId,
    UUID taskId,
    UUID atlasCompanyId,
    UUID searchBatchId,
    RiskType riskType,
    String sourceProvider,
    String sourceUrl,
    String normalizedUrl,
    String sourceDomain,
    String title,
    String snippet,
    String query,
    int rank,
    Instant publishedAt,
    Instant capturedAt,
    String contentHash,
    EntityMatchStatus entityMatchStatus,
    EvidenceVerificationStatus verificationStatus,
    EvidenceGrade grade,
    Map<String, String> metadata
) {
    public PublicEvidence {
        if (evidenceId == null || taskId == null || atlasCompanyId == null || searchBatchId == null) {
            throw new IllegalArgumentException("evidence identifiers are required");
        }
        riskType = riskType == null ? RiskType.OTHER : riskType;
        if (sourceProvider == null || sourceProvider.isBlank()) {
            throw new IllegalArgumentException("sourceProvider must not be blank");
        }
        if (title == null || title.isBlank() || query == null || query.isBlank()) {
            throw new IllegalArgumentException("title and query are required");
        }
        snippet = snippet == null ? "" : snippet;
        if (rank < 1 || capturedAt == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("rank, capturedAt and contentHash are required");
        }
        entityMatchStatus = entityMatchStatus == null
            ? EntityMatchStatus.POSSIBLE_MATCH
            : entityMatchStatus;
        verificationStatus = verificationStatus == null
            ? EvidenceVerificationStatus.UNVERIFIED
            : verificationStatus;
        grade = grade == null ? EvidenceGrade.LEAD : grade;
        metadata = metadata == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public PublicEvidence withVerificationStatus(EvidenceVerificationStatus status) {
        if (status == null || status == EvidenceVerificationStatus.UNVERIFIED) {
            throw new IllegalArgumentException("decision status must be CONFIRMED or REJECTED");
        }
        return new PublicEvidence(
            evidenceId,
            taskId,
            atlasCompanyId,
            searchBatchId,
            riskType,
            sourceProvider,
            sourceUrl,
            normalizedUrl,
            sourceDomain,
            title,
            snippet,
            query,
            rank,
            publishedAt,
            capturedAt,
            contentHash,
            entityMatchStatus,
            status,
            grade,
            metadata
        );
    }

    public PublicEvidence withMetadata(Map<String, String> updatedMetadata) {
        return new PublicEvidence(
            evidenceId, taskId, atlasCompanyId, searchBatchId, riskType,
            sourceProvider, sourceUrl, normalizedUrl, sourceDomain, title,
            snippet, query, rank, publishedAt, capturedAt, contentHash,
            entityMatchStatus, verificationStatus, grade, updatedMetadata
        );
    }

    public PublicEvidence withRiskType(RiskType updatedRiskType) {
        return new PublicEvidence(
            evidenceId, taskId, atlasCompanyId, searchBatchId, updatedRiskType,
            sourceProvider, sourceUrl, normalizedUrl, sourceDomain, title,
            snippet, query, rank, publishedAt, capturedAt, contentHash,
            entityMatchStatus, verificationStatus, grade, metadata
        );
    }
}
