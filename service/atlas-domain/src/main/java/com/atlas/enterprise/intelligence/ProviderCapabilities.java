package com.atlas.enterprise.intelligence;

public record ProviderCapabilities(
    String provider,
    ProviderMode mode,
    boolean returnsAccessibleCitations,
    boolean required
) {
    public ProviderCapabilities {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        mode = mode == null ? ProviderMode.SEARCH_ENGINE : mode;
    }

    public enum ProviderMode {
        SEARCH_ENGINE,
        LLM_SEARCH,
        FIXTURE,
        UNCONFIGURED
    }
}
