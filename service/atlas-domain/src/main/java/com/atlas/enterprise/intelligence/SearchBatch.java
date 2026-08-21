package com.atlas.enterprise.intelligence;

import java.time.Instant;
import java.util.List;

public record SearchBatch(
    String provider,
    SearchBatchStatus status,
    List<SearchResult> results,
    String failureCode,
    String failureMessage,
    Instant searchedAt
) {
    public SearchBatch {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        results = results == null ? List.of() : List.copyOf(results);
        searchedAt = searchedAt == null ? Instant.EPOCH : searchedAt;
        if (status == SearchBatchStatus.SUCCESS_WITH_RESULTS && results.isEmpty()) {
            throw new IllegalArgumentException("successful result batch must contain results");
        }
        if (status == SearchBatchStatus.SUCCESS_EMPTY && !results.isEmpty()) {
            throw new IllegalArgumentException("empty result batch must not contain results");
        }
        if (status == SearchBatchStatus.FAILED && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("failed batch must contain a failure code");
        }
    }

    public static SearchBatch results(
        String provider,
        List<SearchResult> results,
        Instant searchedAt
    ) {
        return results == null || results.isEmpty()
            ? empty(provider, searchedAt)
            : new SearchBatch(
                provider,
                SearchBatchStatus.SUCCESS_WITH_RESULTS,
                results,
                null,
                null,
                searchedAt
            );
    }

    public static SearchBatch empty(String provider, Instant searchedAt) {
        return new SearchBatch(
            provider,
            SearchBatchStatus.SUCCESS_EMPTY,
            List.of(),
            null,
            null,
            searchedAt
        );
    }

    public static SearchBatch failed(
        String provider,
        String failureCode,
        String failureMessage,
        Instant searchedAt
    ) {
        return new SearchBatch(
            provider,
            SearchBatchStatus.FAILED,
            List.of(),
            failureCode,
            failureMessage,
            searchedAt
        );
    }
}
