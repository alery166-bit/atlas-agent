package com.atlas.enterprise.task.api;

import jakarta.validation.constraints.Size;

public record OperatorConfirmationRequest(
    @Size(max = 1000) String note
) {
}
