package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.ConfigurationVersionStatus;
import com.atlas.enterprise.configuration.application.ConfigurationConflictException;
import com.atlas.enterprise.configuration.application.ConfigurationNotFoundException;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import com.atlas.enterprise.risk.RiskRuleReplayRun;
import com.atlas.enterprise.risk.RiskType;
import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.risk.LegacyRiskFeatures;
import com.atlas.enterprise.risk.LegacyRiskScoreEngineV1;
import com.atlas.enterprise.risk.RiskScoreRequest;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.risk.port.RiskRuleReplayRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskRuleReplayApplicationService {
    private final ConfigurationRepository configurations;
    private final RiskRuleReplayRepository replays;
    private final RiskRulePolicyCodec codec;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LegacyRiskScoreEngineV1 engine = new LegacyRiskScoreEngineV1();

    public RiskRuleReplayApplicationService(
        ConfigurationRepository configurations,
        RiskRuleReplayRepository replays,
        RiskRulePolicyCodec codec,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.configurations = configurations;
        this.replays = replays;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public RiskRuleReplayRun replay(
        UUID versionId,
        List<ReplaySample> samples,
        String operatorId
    ) {
        ConfigurationVersion version = requireRuleVersion(versionId);
        if (version.status() != ConfigurationVersionStatus.DRAFT
            && version.status() != ConfigurationVersionStatus.VALIDATED) {
            throw new ConfigurationConflictException(
                "Only draft or validated rule versions can be replayed"
            );
        }
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("At least one replay sample is required");
        }
        RiskRulePolicyCodec.ParsedPolicy policy = codec.parse(
            version.valueJson(), versionLabel(version)
        );
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int scoreChanged = 0;
        int levelChanged = 0;
        BigDecimal maximumDelta = BigDecimal.ZERO;

        for (ReplaySample sample : samples) {
            validateSample(sample);
            List<ConfirmedRiskEvent> events = new ArrayList<>();
            int eventIndex = 0;
            for (String value : sample.riskTypes()) {
                RiskType type;
                try {
                    type = RiskType.valueOf(value.trim().toUpperCase());
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                        "Unknown replay risk type: " + value, exception
                    );
                }
                eventIndex++;
                events.add(new ConfirmedRiskEvent(
                    type,
                    sample.sampleId() + "-event-" + eventIndex,
                    type.displayName(),
                    List.of(sample.sampleId() + "-evidence-" + eventIndex)
                ));
            }
            Instant replayAt = clock.instant();
            RiskScoreRequest scoreRequest = new RiskScoreRequest(
                deterministicUuid(sample.sampleId() + ":task"),
                deterministicUuid(sample.sampleId() + ":snapshot"),
                replayCompany(sample.sampleId(), replayAt),
                events,
                sample.legacyScore(),
                sample.legacyFeatures() == null
                    ? LegacyRiskFeatures.incomplete()
                    : sample.legacyFeatures(),
                policy.runtime(),
                policy.runtime().version(),
                replayAt
            );
            BigDecimal candidate = engine.calculate(scoreRequest).originalScore();
            BigDecimal delta = candidate.subtract(sample.expectedScore()).abs();
            String candidateLevel = policy.thresholds().level(candidate);
            boolean levelMatches = candidateLevel.equals(sample.expectedLevel());
            boolean samplePassed = delta.compareTo(policy.replayGate().maxScoreDelta()) <= 0
                && (policy.replayGate().allowLevelChanges() || levelMatches);
            if (samplePassed) passed++;
            if (candidate.compareTo(sample.legacyScore()) != 0) scoreChanged++;
            if (!levelMatches) levelChanged++;
            maximumDelta = maximumDelta.max(delta);
            results.add(Map.of(
                "sample_id", sample.sampleId(),
                "legacy_score", sample.legacyScore(),
                "candidate_score", candidate,
                "expected_score", sample.expectedScore(),
                "score_delta", delta,
                "candidate_level", candidateLevel,
                "expected_level", sample.expectedLevel(),
                "passed", samplePassed
            ));
        }

        boolean gatePassed = samples.size() >= policy.replayGate().minimumSamples()
            && passed == samples.size();
        RiskRuleReplayRun run = new RiskRuleReplayRun(
            UUID.randomUUID(), versionId, version.checksum(),
            gatePassed ? RiskRuleReplayRun.Status.PASSED : RiskRuleReplayRun.Status.FAILED,
            samples.size(), passed, scoreChanged, levelChanged, maximumDelta,
            writeJson(Map.of(
                "minimum_samples", policy.replayGate().minimumSamples(),
                "sample_gate_met", samples.size() >= policy.replayGate().minimumSamples(),
                "results", results
            )),
            required(operatorId, "operatorId"), clock.instant()
        );
        return replays.save(run);
    }

    @Transactional(readOnly = true)
    public List<RiskRuleReplayRun> history(UUID versionId) {
        requireRuleVersion(versionId);
        return replays.findByVersion(versionId);
    }

    @Transactional(readOnly = true)
    public long usage(UUID versionId) {
        requireRuleVersion(versionId);
        return replays.countTasksUsingVersion(versionId);
    }

    private ConfigurationVersion requireRuleVersion(UUID versionId) {
        ConfigurationVersion version = configurations.findVersion(versionId)
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration version not found: " + versionId
            ));
        ConfigurationDefinition definition = configurations.findDefinitions().stream()
            .filter(item -> item.configId().equals(version.configId()))
            .findFirst()
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration definition not found for version " + versionId
            ));
        if (definition.category() != ConfigurationCategory.RULES) {
            throw new IllegalArgumentException("Configuration version is not a risk rule policy");
        }
        return version;
    }

    private static void validateSample(ReplaySample sample) {
        if (sample == null || sample.sampleId() == null || sample.sampleId().isBlank()
            || sample.legacyScore() == null || sample.expectedScore() == null
            || sample.expectedLevel() == null || sample.expectedLevel().isBlank()) {
            throw new IllegalArgumentException("Replay sample fields are required");
        }
        for (BigDecimal value : List.of(sample.legacyScore(), sample.expectedScore())) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.TEN) > 0) {
                throw new IllegalArgumentException("Replay scores must be in [0,10]");
            }
        }
        if (!List.of("LOW", "MEDIUM_LOW", "MEDIUM", "MEDIUM_HIGH", "HIGH")
            .contains(sample.expectedLevel())) {
            throw new IllegalArgumentException("Replay expected level is invalid");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize replay result", exception);
        }
    }

    private static String versionLabel(ConfigurationVersion version) {
        return RiskRulePolicyCodec.CONFIG_KEY + "/v" + version.versionNo()
            + "@" + version.checksum().substring(0, 8);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static CompanyFacts replayCompany(String sampleId, Instant at) {
        return new CompanyFacts(
            "规则回放样本-" + sampleId,
            null,
            "replay-registration",
            "回放法人",
            "存续",
            "回放地址",
            "有限责任公司",
            "0 万元",
            "2020-01-01",
            "回放登记机关",
            "规则回放",
            "回放行业",
            "GOLDEN_REPLAY",
            sampleId,
            at,
            at,
            Map.of()
        );
    }

    public record ReplaySample(
        String sampleId,
        BigDecimal legacyScore,
        List<String> riskTypes,
        BigDecimal expectedScore,
        String expectedLevel,
        LegacyRiskFeatures legacyFeatures
    ) {
        public ReplaySample {
            riskTypes = riskTypes == null ? List.of() : List.copyOf(riskTypes);
            expectedLevel = expectedLevel == null ? null : expectedLevel.trim().toUpperCase();
        }
    }
}
