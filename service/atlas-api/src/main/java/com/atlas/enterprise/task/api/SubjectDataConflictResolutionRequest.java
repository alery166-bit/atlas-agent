package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.SubjectDataConflictDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubjectDataConflictResolutionRequest(
    @NotNull SubjectDataConflictDecision decision,
    @NotBlank @Size(max = 1000) String note
) {
}
