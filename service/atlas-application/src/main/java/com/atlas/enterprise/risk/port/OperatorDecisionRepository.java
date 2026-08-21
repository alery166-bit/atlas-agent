package com.atlas.enterprise.risk.port;

import com.atlas.enterprise.risk.OperatorDecision;
import java.util.List;
import java.util.UUID;

public interface OperatorDecisionRepository {
    OperatorDecision save(OperatorDecision decision);

    List<OperatorDecision> findByTaskId(UUID taskId);

    List<OperatorDecision> findByTaskIds(List<UUID> taskIds);
}
