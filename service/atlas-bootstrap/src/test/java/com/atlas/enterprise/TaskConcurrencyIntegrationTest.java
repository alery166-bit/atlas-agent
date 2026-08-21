package com.atlas.enterprise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.enterprise.task.application.CreateTaskCommand;
import com.atlas.enterprise.task.application.TaskApplicationService;
import com.atlas.enterprise.task.application.TaskView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TaskConcurrencyIntegrationTest {
    @Autowired
    TaskApplicationService tasks;

    @Autowired
    JdbcClient jdbc;

    @Test
    void concurrentIdempotentCreatesProduceOneTaskAndOneEvent()
        throws Exception {
        String idempotencyKey = "concurrent-" + UUID.randomUUID();
        CreateTaskCommand command = new CreateTaskCommand(
            "更新并发幂等测试有限公司的风险报告",
            "并发幂等测试有限公司",
            "report-v1",
            "concurrency-operator",
            idempotencyKey
        );
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);

        List<TaskView> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<TaskView>> futures =
                java.util.stream.IntStream.range(0, callers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return tasks.create(command);
                    }))
                    .toList();
            ready.await();
            start.countDown();
            results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }

        Set<UUID> taskIds = results.stream()
            .map(TaskView::taskId)
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(1, taskIds.size());
        assertEquals(
            1,
            jdbc.sql("""
                SELECT COUNT(*)
                  FROM investigation_task
                 WHERE idempotency_key = :idempotencyKey
                """)
                .param("idempotencyKey", idempotencyKey)
                .query(Integer.class)
                .single()
        );
        assertEquals(
            1,
            jdbc.sql("""
                SELECT COUNT(*)
                  FROM task_event
                 WHERE task_id = :taskId
                   AND event_type = 'task.status.changed'
                """)
                .param("taskId", taskIds.iterator().next())
                .query(Integer.class)
                .single()
        );
    }
}
