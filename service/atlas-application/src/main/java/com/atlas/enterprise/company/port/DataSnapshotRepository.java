package com.atlas.enterprise.company.port;

import com.atlas.enterprise.company.DataSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DataSnapshotRepository {
    DataSnapshot save(DataSnapshot snapshot);

    Optional<DataSnapshot> findById(UUID snapshotId);

    Optional<DataSnapshot> findLatestByTaskId(UUID taskId);

    Map<UUID, DataSnapshot> findLatestByTaskIds(List<UUID> taskIds);

    int nextVersion(UUID taskId);
}
