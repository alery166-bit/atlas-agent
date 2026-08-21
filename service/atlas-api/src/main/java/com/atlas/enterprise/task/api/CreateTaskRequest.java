package com.atlas.enterprise.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
    @NotBlank @Size(max = 4000) String prompt,
    @NotBlank @Size(max = 256) String companyQuery,
    @Size(max = 256) String previousReportFileId
) {
}
