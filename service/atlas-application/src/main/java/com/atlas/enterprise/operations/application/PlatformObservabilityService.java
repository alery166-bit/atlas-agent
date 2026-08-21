package com.atlas.enterprise.operations.application;

import com.atlas.enterprise.operations.port.PlatformObservabilityPort;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.AuditEntry;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.AuditFilter;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.ConfigurationChange;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.OperationsSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlatformObservabilityService {
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private final PlatformObservabilityPort port;
    private final Clock clock;

    public PlatformObservabilityService(PlatformObservabilityPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    public OperationsSnapshot observe(Instant from, Instant to, int failureLimit) {
        Instant resolvedTo = to == null ? clock.instant() : to;
        Instant resolvedFrom = from == null ? resolvedTo.minus(DEFAULT_WINDOW) : from;
        requireWindow(resolvedFrom, resolvedTo);
        return port.observe(resolvedFrom, resolvedTo, clamp(failureLimit, 1, 100));
    }

    public List<AuditEntry> audit(AuditFilter filter) {
        Instant resolvedTo = filter.to() == null ? clock.instant() : filter.to();
        Instant resolvedFrom = filter.from() == null
            ? resolvedTo.minus(DEFAULT_WINDOW) : filter.from();
        requireWindow(resolvedFrom, resolvedTo);
        return port.audit(new AuditFilter(
            filter.taskId(), trim(filter.enterprise()), trim(filter.operatorId()),
            trim(filter.eventType()), resolvedFrom, resolvedTo,
            clamp(filter.limit(), 1, 500)
        ));
    }

    public ConfigurationChange configurationChange(UUID releaseId) {
        return port.configurationChange(releaseId);
    }

    public void recordRetry(UUID taskId, String operatorId, String traceId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operator id is required for retry audit");
        }
        port.recordRetry(taskId, operatorId.trim(), traceId, clock.instant());
    }

    private static void requireWindow(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be earlier than to");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("time window cannot exceed 366 days");
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
