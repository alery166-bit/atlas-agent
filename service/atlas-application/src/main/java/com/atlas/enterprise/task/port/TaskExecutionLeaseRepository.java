package com.atlas.enterprise.task.port;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface TaskExecutionLeaseRepository {
    boolean tryAcquire(UUID taskId, String owner, Instant now, Duration leaseDuration);

    void heartbeat(UUID taskId, String owner, Instant now, Duration leaseDuration);

    void release(UUID taskId, String owner);
}
