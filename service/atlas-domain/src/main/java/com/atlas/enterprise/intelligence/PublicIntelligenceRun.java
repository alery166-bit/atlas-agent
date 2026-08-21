package com.atlas.enterprise.intelligence;

import java.util.List;
import java.util.UUID;

public record PublicIntelligenceRun(
    UUID taskId,
    List<SearchExecution> searches,
    List<PublicEvidence> evidence
) {
    public PublicIntelligenceRun {
        searches = searches == null ? List.of() : List.copyOf(searches);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
