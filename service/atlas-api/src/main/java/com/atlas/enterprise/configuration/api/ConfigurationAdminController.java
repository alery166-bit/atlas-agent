package com.atlas.enterprise.configuration.api;

import com.atlas.enterprise.configuration.ConfigurationBinding;
import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.TaskConfigurationSnapshot;
import com.atlas.enterprise.configuration.application.ConfigurationApplicationService;
import com.atlas.enterprise.configuration.application.ConfigurationOverview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/configurations")
public class ConfigurationAdminController {
    private final ConfigurationApplicationService configurations;

    public ConfigurationAdminController(ConfigurationApplicationService configurations) {
        this.configurations = configurations;
    }

    @GetMapping
    public List<OverviewResponse> list(
        @RequestParam(defaultValue = "DEV") String environment
    ) {
        return configurations.list(environment).stream()
            .map(OverviewResponse::from).toList();
    }

    @PostMapping
    public OverviewResponse create(@Valid @RequestBody CreateRequest request) {
        return OverviewResponse.from(configurations.create(
            request.configKey(), request.category(), request.displayName(),
            request.description(), request.secretConfig(), request.valueJson(),
            request.secretRef(), request.operatorId()
        ));
    }

    @PostMapping("/{configKey}/drafts")
    public VersionResponse draft(
        @PathVariable String configKey,
        @Valid @RequestBody DraftRequest request
    ) {
        ConfigurationVersion version = configurations.createDraft(
            configKey, request.valueJson(), request.secretRef(), request.operatorId()
        );
        return VersionResponse.from(version, request.secretRef() != null, false);
    }

    @PutMapping("/versions/{versionId}")
    public VersionResponse updateDraft(
        @PathVariable UUID versionId,
        @Valid @RequestBody UpdateDraftRequest request
    ) {
        ConfigurationVersion version = configurations.updateDraft(
            versionId, request.expectedRowVersion(), request.valueJson(),
            request.secretRef(), request.operatorId()
        );
        return VersionResponse.from(version, version.secretRef() != null, false);
    }

    @PostMapping("/versions/{versionId}/validate")
    public VersionResponse validate(
        @PathVariable UUID versionId,
        @Valid @RequestBody ValidateRequest request
    ) {
        ConfigurationVersion version = configurations.validate(
            versionId, request.expectedRowVersion(), request.operatorId()
        );
        return VersionResponse.from(version, version.secretRef() != null, false);
    }

    @PostMapping("/versions/{versionId}/publish")
    public ConfigurationBinding publish(
        @PathVariable UUID versionId,
        @Valid @RequestBody ReleaseRequest request
    ) {
        return configurations.publish(
            versionId, request.environment(), request.idempotencyKey(), request.operatorId()
        );
    }

    @PostMapping("/versions/{versionId}/rollback")
    public ConfigurationBinding rollback(
        @PathVariable UUID versionId,
        @Valid @RequestBody ReleaseRequest request
    ) {
        return configurations.rollback(
            versionId, request.environment(), request.idempotencyKey(), request.operatorId()
        );
    }

    @PostMapping("/tasks/{taskId}/snapshot")
    public TaskConfigurationSnapshot taskSnapshot(
        @PathVariable UUID taskId,
        @RequestParam(defaultValue = "DEV") String environment
    ) {
        return configurations.snapshotForTask(taskId, environment);
    }

    public record CreateRequest(
        @NotBlank String configKey,
        @NotNull ConfigurationCategory category,
        @NotBlank String displayName,
        String description,
        boolean secretConfig,
        @NotBlank String valueJson,
        String secretRef,
        @NotBlank String operatorId
    ) {}

    public record DraftRequest(
        @NotBlank String valueJson,
        String secretRef,
        @NotBlank String operatorId
    ) {}

    public record ValidateRequest(
        long expectedRowVersion,
        @NotBlank String operatorId
    ) {}

    public record UpdateDraftRequest(
        long expectedRowVersion,
        @NotBlank String valueJson,
        String secretRef,
        @NotBlank String operatorId
    ) {}

    public record ReleaseRequest(
        @NotBlank String environment,
        @NotBlank String idempotencyKey,
        @NotBlank String operatorId
    ) {}

    public record OverviewResponse(
        ConfigurationDefinition definition,
        List<VersionResponse> versions,
        ConfigurationBinding binding
    ) {
        static OverviewResponse from(ConfigurationOverview overview) {
            boolean secret = overview.definition().secretConfig();
            return new OverviewResponse(
                overview.definition(),
                overview.versions().stream()
                    .map(version -> VersionResponse.from(
                        version, version.secretRef() != null, secret
                    )).toList(),
                overview.binding()
            );
        }
    }

    public record VersionResponse(
        UUID versionId,
        int versionNo,
        String status,
        String valueJson,
        boolean secretConfigured,
        String checksum,
        String validationMessage,
        String createdBy,
        Instant createdAt,
        String validatedBy,
        Instant validatedAt,
        String publishedBy,
        Instant publishedAt,
        long rowVersion
    ) {
        static VersionResponse from(
            ConfigurationVersion version,
            boolean secretConfigured,
            boolean hideValue
        ) {
            return new VersionResponse(
                version.versionId(), version.versionNo(), version.status().name(),
                hideValue ? null : version.valueJson(), secretConfigured,
                version.checksum(), version.validationMessage(),
                version.createdBy(), version.createdAt(),
                version.validatedBy(), version.validatedAt(),
                version.publishedBy(), version.publishedAt(), version.rowVersion()
            );
        }
    }
}
