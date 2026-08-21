package com.atlas.enterprise.intelligence.application;

import java.util.UUID;

public class EvidenceNotFoundException extends RuntimeException {
    private final UUID evidenceId;

    public EvidenceNotFoundException(UUID evidenceId) {
        super("Evidence not found: " + evidenceId);
        this.evidenceId = evidenceId;
    }

    public UUID evidenceId() {
        return evidenceId;
    }
}
