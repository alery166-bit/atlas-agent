package com.atlas.enterprise.intelligence.port;

import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchRequest;

public interface PublicSearchProvider {
    SearchBatch search(SearchRequest request);

    ProviderCapabilities capabilities();
}
