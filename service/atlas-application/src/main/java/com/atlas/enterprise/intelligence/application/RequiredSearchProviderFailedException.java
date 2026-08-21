package com.atlas.enterprise.intelligence.application;

public class RequiredSearchProviderFailedException extends RuntimeException {
    private final String provider;
    private final String failureCode;

    public RequiredSearchProviderFailedException(
        String provider,
        String failureCode,
        String message
    ) {
        super(message == null || message.isBlank()
            ? "Required search provider failed: " + provider
            : message);
        this.provider = provider;
        this.failureCode = failureCode;
    }

    public String provider() {
        return provider;
    }

    public String failureCode() {
        return failureCode;
    }
}
