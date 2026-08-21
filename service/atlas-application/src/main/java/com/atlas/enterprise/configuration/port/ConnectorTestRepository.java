package com.atlas.enterprise.configuration.port;

import com.atlas.enterprise.configuration.ConnectorTestRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorTestRepository {
    ConnectorTestRun save(ConnectorTestRun run);
    Optional<ConnectorTestRun> findLatest(UUID versionId);
    List<ConnectorTestRun> findByVersion(UUID versionId);
}
