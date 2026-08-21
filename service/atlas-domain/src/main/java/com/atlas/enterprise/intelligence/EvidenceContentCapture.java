package com.atlas.enterprise.intelligence;

import java.time.Instant;

public record EvidenceContentCapture(
    EvidenceContentStatus status,
    String requestedUrl,
    String finalUrl,
    Integer httpStatus,
    String contentType,
    String rawContent,
    String extractedText,
    String rawContentHash,
    String extractedTextHash,
    long byteLength,
    boolean truncated,
    String failureCode,
    String failureMessage,
    Instant capturedAt
) {
    public EvidenceContentCapture {
        if (status == null || requestedUrl == null || requestedUrl.isBlank()
            || capturedAt == null || byteLength < 0) {
            throw new IllegalArgumentException("invalid evidence content capture");
        }
        if (status == EvidenceContentStatus.CAPTURED
            && (extractedText == null || extractedText.isBlank()
                || rawContentHash == null || extractedTextHash == null)) {
            throw new IllegalArgumentException(
                "captured content requires text and hashes"
            );
        }
        if (status == EvidenceContentStatus.FAILED
            && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException(
                "failed content capture requires a failure code"
            );
        }
    }

    public static EvidenceContentCapture failed(
        String requestedUrl,
        String failureCode,
        String failureMessage,
        Instant capturedAt
    ) {
        return new EvidenceContentCapture(
            EvidenceContentStatus.FAILED,
            requestedUrl,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            false,
            failureCode,
            failureMessage,
            capturedAt
        );
    }
}
