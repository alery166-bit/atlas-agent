package com.atlas.enterprise.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubjectConfirmationRequest(
    @NotBlank @Size(max = 64) String sourceSystem,
    @NotBlank @Size(max = 128) String sourceEntityId
) {
}
