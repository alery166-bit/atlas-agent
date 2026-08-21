package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.SubjectDataConflictResolution;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SubjectDataConflictResolutionRepository {
    SubjectDataConflictResolution save(SubjectDataConflictResolution resolution);
    Optional<SubjectDataConflictResolution> findLatestByTaskId(UUID taskId);
    Map<UUID, SubjectDataConflictResolution> findLatestByTaskIds(List<UUID> taskIds);
}
