package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.intelligence.port.PublicSearchProvider;

/**
 * Safe local fallback: missing live credentials is a source failure, not an
 * empty search result.
 */
public class UnconfiguredPublicSearchProvider implements PublicSearchProvider {
    private static final String PROVIDER = "unconfigured-search";

    @Override
    public SearchBatch search(SearchRequest request) {
        return SearchBatch.failed(
            PROVIDER,
            "SEARCH_PROVIDER_NOT_CONFIGURED",
            "Live public-search credentials are not configured",
            request.requestedAt()
        );
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
            PROVIDER,
            ProviderCapabilities.ProviderMode.UNCONFIGURED,
            false,
            true
        );
    }
}
