package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.EvidenceContentCapture;
import com.atlas.enterprise.intelligence.port.EvidenceContentFetcher;
import java.time.Instant;

public class UnconfiguredEvidenceContentFetcher implements EvidenceContentFetcher {
    @Override
    public EvidenceContentCapture fetch(String url) {
        return EvidenceContentCapture.failed(
            url,
            "CONTENT_FETCH_NOT_CONFIGURED",
            "Evidence content fetching is disabled",
            Instant.now()
        );
    }
}
