package com.atlas.enterprise.intelligence.port;

import java.util.List;
import java.util.UUID;

public interface PublicSearchProviderRegistry {
    List<PublicSearchProvider> providers(UUID taskId);

    default List<PublicSearchProvider> providers() {
        return providers(null);
    }
}
