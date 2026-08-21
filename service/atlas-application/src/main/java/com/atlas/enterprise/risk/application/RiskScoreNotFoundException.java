package com.atlas.enterprise.risk.application;

import java.util.UUID;

public class RiskScoreNotFoundException extends RuntimeException {
    private final UUID targetId;

    public RiskScoreNotFoundException(UUID targetId) {
        this("Risk score snapshot not found: " + targetId, targetId);
    }

    public RiskScoreNotFoundException(String message, UUID targetId) {
        super(message);
        this.targetId = targetId;
    }

    public UUID targetId() {
        return targetId;
    }
}
