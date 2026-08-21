package com.atlas.enterprise.intelligence.application;

public class PublicIntelligenceValidationException extends RuntimeException {
    public PublicIntelligenceValidationException(String message) {
        super(message);
    }

    public PublicIntelligenceValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
