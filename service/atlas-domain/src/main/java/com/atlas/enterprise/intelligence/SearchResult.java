package com.atlas.enterprise.intelligence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record SearchResult(
    String providerResultId,
    String title,
    String url,
    String snippet,
    String rawContent,
    Instant publishedAt,
    int rank,
    Map<String, String> metadata
) {
    public SearchResult(
        String providerResultId,
        String title,
        String url,
        String snippet,
        Instant publishedAt,
        int rank,
        Map<String, String> metadata
    ) {
        this(providerResultId, title, url, snippet, null, publishedAt, rank, metadata);
    }

    public SearchResult {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        snippet = snippet == null ? "" : snippet;
        rawContent = rawContent == null || rawContent.isBlank()
            ? null
            : rawContent;
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be at least 1");
        }
        metadata = metadata == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
