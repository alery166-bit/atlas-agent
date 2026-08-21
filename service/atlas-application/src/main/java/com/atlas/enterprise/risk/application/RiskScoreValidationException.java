package com.atlas.enterprise.risk.application;

public class RiskScoreValidationException extends RuntimeException {
    public RiskScoreValidationException(String message) {
        super(message);
    }

    public RiskScoreValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
