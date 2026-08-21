package com.atlas.enterprise.company.api;

import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.intelligence.application.CompanyAliasApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/company-aliases")
public class CompanyAliasController {
    private final CompanyAliasApplicationService companyAliases;

    public CompanyAliasController(CompanyAliasApplicationService companyAliases) {
        this.companyAliases = companyAliases;
    }

    @GetMapping
    public List<CompanyAlias> list(@PathVariable UUID taskId) {
        return companyAliases.list(taskId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyAlias add(
        @PathVariable UUID taskId,
        @Valid @RequestBody CompanyAliasRequest request,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String operatorId
    ) {
        return companyAliases.addConfirmed(
            taskId,
            request.aliasName(),
            request.aliasType(),
            request.relation(),
            request.sourceEvidence(),
            operatorId
        );
    }
}
