package com.atlas.enterprise.task.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.enterprise.task.application.TaskEventRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservableTaskEventPublisherTest {
    @Test
    void recordsBoundedBusinessMetricsAndTerminalDuration() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservableTaskEventPublisher publisher =
            new ObservableTaskEventPublisher(
                new SseTaskEventPublisher(),
                meters
            );
        UUID taskId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-07-30T00:00:00Z");

        publisher.publish(event(
            1,
            taskId,
            "task.status.changed",
            Map.of("status", "CREATED"),
            startedAt
        ));
        publisher.publish(event(
            2,
            taskId,
            "operator.action.required",
            Map.of("action", "REVIEW_EVIDENCE"),
            startedAt.plusSeconds(30)
        ));
        publisher.publish(event(
            3,
            taskId,
            "public.intelligence.evidence.decided",
            Map.of("decision", "CONFIRMED"),
            startedAt.plusSeconds(60)
        ));
        publisher.publish(event(
            4,
            taskId,
            "risk.score.calculated",
            Map.of("riskLevel", "HIGH"),
            startedAt.plusSeconds(90)
        ));
        publisher.publish(event(
            5,
            taskId,
            "report.generated",
            Map.of("reportId", UUID.randomUUID().toString()),
            startedAt.plusSeconds(110)
        ));
        publisher.publish(event(
            6,
            taskId,
            "task.status.changed",
            Map.of("status", "COMPLETED"),
            startedAt.plusSeconds(120)
        ));

        assertEquals(
            6.0D,
            meters.get("atlas.business.events")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum()
        );
        assertEquals(
            1.0D,
            meters.get("atlas.business.operator.actions")
                .tag("action", "review_evidence")
                .counter()
                .count()
        );
        assertEquals(
            1.0D,
            meters.get("atlas.business.evidence.decisions")
                .tag("decision", "confirmed")
                .counter()
                .count()
        );
        assertEquals(
            1.0D,
            meters.get("atlas.business.risk.scores")
                .tag("action", "calculated")
                .tag("risk_level", "high")
                .counter()
                .count()
        );
        assertEquals(
            1.0D,
            meters.get("atlas.business.reports")
                .tag("outcome", "generated")
                .counter()
                .count()
        );
        assertEquals(
            1,
            meters.get("atlas.business.task.terminal.duration")
                .tag("terminal_status", "completed")
                .timer()
                .count()
        );
        assertEquals(
            120.0D,
            meters.get("atlas.business.task.terminal.duration")
                .tag("terminal_status", "completed")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)
        );
    }

    private static TaskEventRecord event(
        long eventId,
        UUID taskId,
        String type,
        Map<String, String> payload,
        Instant occurredAt
    ) {
        return new TaskEventRecord(
            eventId,
            taskId,
            type,
            payload,
            occurredAt
        );
    }
}
