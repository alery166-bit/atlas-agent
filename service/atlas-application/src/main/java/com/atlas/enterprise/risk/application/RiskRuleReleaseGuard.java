package com.atlas.enterprise.risk.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.application.ConfigurationConflictException;
import com.atlas.enterprise.configuration.application.ConfigurationReleaseGuard;
import com.atlas.enterprise.risk.RiskRuleReplayRun;
import com.atlas.enterprise.risk.port.RiskRuleReplayRepository;
import org.springframework.stereotype.Component;

@Component
public class RiskRuleReleaseGuard implements ConfigurationReleaseGuard {
    private final RiskRuleReplayRepository replays;

    public RiskRuleReleaseGuard(RiskRuleReplayRepository replays) {
        this.replays = replays;
    }

    @Override
    public boolean supports(ConfigurationCategory category) {
        return category == ConfigurationCategory.RULES;
    }

    @Override
    public void checkPublish(ConfigurationVersion version, String environment) {
        RiskRuleReplayRun replay = replays.findLatest(version.versionId())
            .orElseThrow(() -> new ConfigurationConflictException(
                "Risk rule version must pass a golden sample replay before publishing"
            ));
        if (!version.checksum().equals(replay.versionChecksum())) {
            throw new ConfigurationConflictException(
                "Risk rule draft changed after the latest golden sample replay"
            );
        }
        if (replay.status() != RiskRuleReplayRun.Status.PASSED) {
            throw new ConfigurationConflictException(
                "Latest golden sample replay did not pass the publishing gate"
            );
        }
    }
}
