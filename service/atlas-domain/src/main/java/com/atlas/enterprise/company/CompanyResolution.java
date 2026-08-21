package com.atlas.enterprise.company;

import java.util.List;

public record CompanyResolution(
    CompanyResolutionStatus status,
    List<CompanyCandidate> candidates,
    List<SourceStatus> sourceStatuses
) {
    public CompanyResolution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        sourceStatuses = sourceStatuses == null ? List.of() : List.copyOf(sourceStatuses);
    }

    public CompanyCandidate uniqueCandidate() {
        if (status != CompanyResolutionStatus.UNIQUE || candidates.size() != 1) {
            throw new IllegalStateException("Resolution does not contain one unique candidate");
        }
        return candidates.getFirst();
    }
}
