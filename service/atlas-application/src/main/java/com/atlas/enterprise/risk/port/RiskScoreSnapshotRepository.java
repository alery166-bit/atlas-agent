package com.atlas.enterprise.risk.port;

import com.atlas.enterprise.risk.RiskScoreSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RiskScoreSnapshotRepository {
    RiskScoreSnapshot save(RiskScoreSnapshot snapshot);

    Optional<RiskScoreSnapshot> findById(UUID scoreSnapshotId);

    Optional<RiskScoreSnapshot> findLatestByTaskId(UUID taskId);

    Map<UUID, RiskScoreSnapshot> findLatestByTaskIds(List<UUID> taskIds);

    Optional<RiskScoreSnapshot> findByInputHash(UUID taskId, String inputHash);
}
