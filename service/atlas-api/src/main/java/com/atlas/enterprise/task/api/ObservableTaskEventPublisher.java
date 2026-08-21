package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class ObservableTaskEventPublisher implements TaskEventPublisher {
    private static final Set<String> TERMINAL_STATUSES = Set.of(
        "completed",
        "source_failed",
        "model_failed",
        "report_failed",
        "cancelled"
    );

    private final SseTaskEventPublisher sse;
    private final MeterRegistry meters;
    private final ConcurrentMap<UUID, Instant> taskStartedAt =
        new ConcurrentHashMap<>();

    public ObservableTaskEventPublisher(
        SseTaskEventPublisher sse,
        MeterRegistry meters
    ) {
        this.sse = sse;
        this.meters = meters;
    }

    @Override
    public void publish(TaskEventRecord event) {
        record(event);
        sse.publish(event);
    }

    private void record(TaskEventRecord event) {
        meters.counter(
            "atlas.business.events",
            "event_type",
            event.type()
        ).increment();
        switch (event.type()) {
            case "task.status.changed" -> recordStatus(event);
            case "operator.action.required" -> counter(
                "atlas.business.operator.actions",
                "action",
                payload(event, "action", "UNKNOWN")
            );
            case "public.intelligence.evidence.decided" -> counter(
                "atlas.business.evidence.decisions",
                "decision",
                payload(event, "decision", "UNKNOWN")
            );
            case "risk.score.calculated", "risk.score.adjusted" ->
                meters.counter(
                    "atlas.business.risk.scores",
                    "action",
                    event.type().endsWith("calculated")
                        ? "calculated"
                        : "adjusted",
                    "risk_level",
                    payload(event, "riskLevel", "UNKNOWN")
                ).increment();
            case "report.generated", "report.failed" -> counter(
                "atlas.business.reports",
                "outcome",
                event.type().endsWith("generated")
                    ? "generated"
                    : "failed"
            );
            case "step.failed" -> meters.counter(
                "atlas.business.workflow.failures",
                "step",
                payload(event, "step", "UNKNOWN"),
                "error_code",
                payload(event, "errorCode", "UNKNOWN")
            ).increment();
            default -> {
                // The common event counter still records unclassified events.
            }
        }
    }

    private void recordStatus(TaskEventRecord event) {
        String status = payload(event, "status", "UNKNOWN");
        counter(
            "atlas.business.task.status.transitions",
            "status",
            status
        );
        if ("created".equals(status)) {
            taskStartedAt.putIfAbsent(event.taskId(), event.occurredAt());
            return;
        }
        if (!TERMINAL_STATUSES.contains(status)) {
            return;
        }
        Instant startedAt = taskStartedAt.remove(event.taskId());
        if (
            startedAt == null
                || event.occurredAt() == null
                || event.occurredAt().isBefore(startedAt)
        ) {
            return;
        }
        Timer.builder("atlas.business.task.terminal.duration")
            .description("Time from task creation to its first terminal state")
            .tag("terminal_status", status)
            .register(meters)
            .record(Duration.between(startedAt, event.occurredAt()));
    }

    private void counter(String metric, String tag, String value) {
        meters.counter(metric, tag, normalized(value)).increment();
    }

    private static String payload(
        TaskEventRecord event,
        String key,
        String fallback
    ) {
        return normalized(event.payload().getOrDefault(key, fallback));
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
