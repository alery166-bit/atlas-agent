package com.atlas.enterprise.company.api;

import com.atlas.enterprise.company.CompanyAliasRelation;
import com.atlas.enterprise.company.CompanyAliasType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CompanyAliasRequest(
    @NotBlank @Size(max = 256) String aliasName,
    @NotNull CompanyAliasType aliasType,
    @NotNull CompanyAliasRelation relation,
    @NotBlank @Size(max = 1000) String sourceEvidence
) {}
