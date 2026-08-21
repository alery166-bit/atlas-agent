package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.OperatorConfirmation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OperatorConfirmationRepository {
    OperatorConfirmation save(OperatorConfirmation confirmation);

    Optional<OperatorConfirmation> findLatestByTaskId(UUID taskId);

    Map<UUID, OperatorConfirmation> findLatestByTaskIds(List<UUID> taskIds);

    List<OperatorConfirmation> findByTaskId(UUID taskId);
}
