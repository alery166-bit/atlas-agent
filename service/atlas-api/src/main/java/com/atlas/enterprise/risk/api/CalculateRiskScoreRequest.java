package com.atlas.enterprise.risk.api;

import jakarta.validation.Valid;
import java.util.List;

public record CalculateRiskScoreRequest(
    List<@Valid ConfirmedRiskEventRequest> confirmedEvents
) {
    public CalculateRiskScoreRequest {
        confirmedEvents = confirmedEvents == null ? List.of() : List.copyOf(confirmedEvents);
    }
}
