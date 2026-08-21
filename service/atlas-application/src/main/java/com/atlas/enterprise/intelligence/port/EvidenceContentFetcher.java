package com.atlas.enterprise.intelligence.port;

import com.atlas.enterprise.intelligence.EvidenceContentCapture;

public interface EvidenceContentFetcher {
    EvidenceContentCapture fetch(String url);
}
