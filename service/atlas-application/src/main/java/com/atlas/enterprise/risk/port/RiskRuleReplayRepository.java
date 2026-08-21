package com.atlas.enterprise.risk.port;

import com.atlas.enterprise.risk.RiskRuleReplayRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskRuleReplayRepository {
    RiskRuleReplayRun save(RiskRuleReplayRun run);
    Optional<RiskRuleReplayRun> findLatest(UUID versionId);
    List<RiskRuleReplayRun> findByVersion(UUID versionId);
    long countTasksUsingVersion(UUID versionId);
}
