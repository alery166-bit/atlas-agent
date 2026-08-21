package com.atlas.enterprise.task.storage;

import com.atlas.enterprise.task.port.TaskExecutionLeaseRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaskExecutionLeaseRepository implements TaskExecutionLeaseRepository {
    private final JdbcClient jdbc;

    public JdbcTaskExecutionLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(UUID taskId, String owner, Instant now, Duration leaseDuration) {
        return jdbc.sql("""
                UPDATE investigation_task
                   SET execution_owner = :owner,
                       lease_expires_at = :expiresAt,
                       heartbeat_at = :now
                 WHERE task_id = :taskId
                   AND (
                       execution_owner IS NULL
                       OR execution_owner = :owner
                       OR lease_expires_at < :now
                   )
                """)
            .param("owner", owner)
            .param("expiresAt", utc(now.plus(leaseDuration)))
            .param("now", utc(now))
            .param("taskId", taskId)
            .update() == 1;
    }

    @Override
    public void heartbeat(UUID taskId, String owner, Instant now, Duration leaseDuration) {
        jdbc.sql("""
                UPDATE investigation_task
                   SET lease_expires_at = :expiresAt,
                       heartbeat_at = :now
                 WHERE task_id = :taskId
                   AND execution_owner = :owner
                """)
            .param("expiresAt", utc(now.plus(leaseDuration)))
            .param("now", utc(now))
            .param("taskId", taskId)
            .param("owner", owner)
            .update();
    }

    @Override
    public void release(UUID taskId, String owner) {
        jdbc.sql("""
                UPDATE investigation_task
                   SET execution_owner = NULL,
                       lease_expires_at = NULL,
                       heartbeat_at = NULL
                 WHERE task_id = :taskId
                   AND execution_owner = :owner
                """)
            .param("taskId", taskId)
            .param("owner", owner)
            .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
