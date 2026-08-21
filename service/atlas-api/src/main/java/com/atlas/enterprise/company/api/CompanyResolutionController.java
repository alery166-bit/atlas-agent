package com.atlas.enterprise.company.api;

import com.atlas.enterprise.company.CompanyResolution;
import com.atlas.enterprise.company.application.CompanyResolutionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/companies")
public class CompanyResolutionController {
    private final CompanyResolutionService resolutionService;

    public CompanyResolutionController(CompanyResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    @GetMapping("/resolve")
    public CompanyResolution resolve(
        @RequestParam
        @NotBlank
        @Size(max = 256)
        String query
    ) {
        return resolutionService.resolve(query);
    }
}
