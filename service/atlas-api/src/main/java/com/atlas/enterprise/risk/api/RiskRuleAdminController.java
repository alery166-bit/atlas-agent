package com.atlas.enterprise.risk.api;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.application.ConfigurationApplicationService;
import com.atlas.enterprise.configuration.application.ConfigurationOverview;
import com.atlas.enterprise.risk.RiskRuleReplayRun;
import com.atlas.enterprise.risk.application.RiskRulePolicyCodec;
import com.atlas.enterprise.risk.application.RiskRuleReplayApplicationService;
import com.atlas.enterprise.risk.application.LegacyRiskTraceabilityCatalog;
import com.atlas.enterprise.risk.LegacyRiskFeatures;
import com.atlas.enterprise.risk.LegacyScoringProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/risk-rules")
public class RiskRuleAdminController {
    private final ConfigurationApplicationService configurations;
    private final RiskRuleReplayApplicationService replays;
    private final RiskRulePolicyCodec codec;
    private final LegacyRiskTraceabilityCatalog traceabilityCatalog;

    public RiskRuleAdminController(
        ConfigurationApplicationService configurations,
        RiskRuleReplayApplicationService replays,
        RiskRulePolicyCodec codec,
        LegacyRiskTraceabilityCatalog traceabilityCatalog
    ) {
        this.configurations = configurations;
        this.replays = replays;
        this.codec = codec;
        this.traceabilityCatalog = traceabilityCatalog;
    }

    @GetMapping("/traceability")
    public LegacyRiskTraceabilityCatalog.Traceability traceability() {
        return traceabilityCatalog.traceability();
    }

    @GetMapping
    public OverviewResponse overview(
        @RequestParam(defaultValue = "DEV") String environment
    ) {
        List<ConfigurationOverview> policies = configurations.list(environment).stream()
            .filter(item -> item.definition().category() == ConfigurationCategory.RULES)
            .toList();
        List<PolicyResponse> values = policies.stream().map(policy ->
            new PolicyResponse(
                policy,
                policy.versions().stream().map(version -> new VersionImpact(
                    version.versionId(),
                    replays.history(version.versionId()).stream().findFirst().orElse(null),
                    replays.usage(version.versionId())
                )).toList()
            )
        ).toList();
        return new OverviewResponse(codec.defaultJson(), values);
    }

    @PostMapping("/initialize")
    public ConfigurationOverview initialize(@Valid @RequestBody InitializeRequest request) {
        return configurations.create(
            RiskRulePolicyCodec.CONFIG_KEY,
            ConfigurationCategory.RULES,
            "企业标准风险评分规则",
            "风险标签、事件最低分、时间窗口、权重和发布门禁",
            false,
            codec.defaultJson(),
            null,
            request.operatorId()
        );
    }

    @PostMapping("/versions/{versionId}/replays")
    public RiskRuleReplayRun replay(
        @PathVariable UUID versionId,
        @Valid @RequestBody ReplayRequest request
    ) {
        return replays.replay(
            versionId,
            request.samples().stream().map(sample ->
                new RiskRuleReplayApplicationService.ReplaySample(
                    sample.sampleId(), sample.legacyScore(), sample.riskTypes(),
                    sample.expectedScore(), sample.expectedLevel(),
                    sample.features() == null ? null : sample.features().toDomain()
                )
            ).toList(),
            request.operatorId()
        );
    }

    @GetMapping("/versions/{versionId}/replays")
    public List<RiskRuleReplayRun> replayHistory(@PathVariable UUID versionId) {
        return replays.history(versionId);
    }

    @GetMapping("/versions/{versionId}/usage")
    public UsageResponse usage(@PathVariable UUID versionId) {
        return new UsageResponse(versionId, replays.usage(versionId));
    }

    public record InitializeRequest(@NotBlank String operatorId) {}

    public record ReplayRequest(
        @NotBlank String operatorId,
        @NotEmpty List<ReplaySampleRequest> samples
    ) {}

    public record ReplaySampleRequest(
        @NotBlank String sampleId,
        BigDecimal legacyScore,
        List<String> riskTypes,
        BigDecimal expectedScore,
        @NotBlank String expectedLevel,
        ReplayFeaturesRequest features
    ) {}

    public record ReplayFeaturesRequest(
        String scoringProfile,
        int sentimentCount,
        int sentimentKeywordCount,
        int complaintCount,
        int complaintKeywordCount,
        int judicialDefendantCount,
        int judicialKeywordCount,
        int businessAbnormalCount,
        int seriousIllegalCount,
        int administrativePenaltyCount,
        int equityPledgeCount,
        int equityFreezeCount,
        int stockPledgeCount,
        Set<Long> industryIds,
        Set<String> riskLabelNos,
        boolean corporateShareholdersChange,
        boolean corporateShareholdersAddressChange,
        BigDecimal paidCapitalTenThousands,
        Integer operatingYears,
        String listingInfo,
        boolean monitorCompany,
        BigDecimal relatedShareholderScore,
        BigDecimal relatedInvestmentScore
    ) {
        LegacyRiskFeatures toDomain() {
            return new LegacyRiskFeatures(
                true,
                LegacyScoringProfile.from(scoringProfile),
                sentimentCount, sentimentKeywordCount,
                complaintCount, complaintKeywordCount,
                judicialDefendantCount, judicialKeywordCount,
                businessAbnormalCount, seriousIllegalCount,
                administrativePenaltyCount, equityPledgeCount,
                equityFreezeCount, stockPledgeCount,
                industryIds, riskLabelNos,
                corporateShareholdersChange,
                corporateShareholdersAddressChange,
                paidCapitalTenThousands, operatingYears,
                listingInfo, monitorCompany,
                relatedShareholderScore, relatedInvestmentScore
            );
        }
    }

    public record OverviewResponse(
        String defaultPolicyJson,
        List<PolicyResponse> policies
    ) {}

    public record PolicyResponse(
        ConfigurationOverview configuration,
        List<VersionImpact> versionImpacts
    ) {}

    public record VersionImpact(
        UUID versionId,
        RiskRuleReplayRun latestReplay,
        long taskUsageCount
    ) {}

    public record UsageResponse(UUID versionId, long taskUsageCount) {}
}
