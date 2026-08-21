package com.atlas.enterprise.task.application;

import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.configuration.application.ConfigurationApplicationService;
import com.atlas.enterprise.configuration.application.PublishedSkillSetGuard;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Service
public class TaskApplicationService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TaskRepository taskRepository;
    private final TaskEventStore taskEventStore;
    private final TaskEventPublisher taskEventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ConfigurationApplicationService configurations;
    private final PublishedSkillSetGuard publishedSkills;
    private final String environment;
    private final ConcurrentMap<String, Object> creationLocks =
        new ConcurrentHashMap<>();

    public TaskApplicationService(
        TaskRepository taskRepository,
        TaskEventStore taskEventStore,
        TaskEventPublisher taskEventPublisher,
        Clock clock,
        PlatformTransactionManager transactionManager,
        ConfigurationApplicationService configurations,
        PublishedSkillSetGuard publishedSkills,
        @Value("${atlas.environment:DEV}") String environment
    ) {
        this.taskRepository = taskRepository;
        this.taskEventStore = taskEventStore;
        this.taskEventPublisher = taskEventPublisher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(
            transactionManager
        );
        this.configurations = configurations;
        this.publishedSkills = publishedSkills;
        this.environment = environment;
    }

    public TaskView create(CreateTaskCommand command) {
        String key = command.idempotencyKey();
        Object lock = creationLocks.computeIfAbsent(key, ignored ->
            new Object()
        );
        try {
            synchronized (lock) {
                return Objects.requireNonNull(
                    transactionTemplate.execute(status ->
                        taskRepository.findByIdempotencyKey(key)
                            .map(TaskView::from)
                            .orElseGet(() -> createNew(command))
                    )
                );
            }
        } finally {
            creationLocks.remove(key, lock);
        }
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID taskId) {
        return TaskView.from(taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId)));
    }

    @Transactional(readOnly = true)
    public List<TaskEventRecord> eventsAfter(UUID taskId, long eventIdExclusive) {
        if (taskRepository.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        return taskEventStore.findAfter(taskId, eventIdExclusive);
    }

    private TaskView createNew(CreateTaskCommand command) {
        publishedSkills.requireReady(environment);
        Instant now = clock.instant();
        UUID taskId = UUID.randomUUID();
        String taskNo = "AT-%s-%s".formatted(
            LocalDate.ofInstant(now, ZoneOffset.UTC).format(DATE),
            taskId.toString().substring(0, 8).toUpperCase()
        );
        InvestigationTask task = InvestigationTask.create(
            taskId,
            taskNo,
            command.prompt(),
            command.companyQuery(),
            command.previousReportFileId(),
            command.operatorId(),
            command.idempotencyKey(),
            now
        );
        taskRepository.save(task);
        configurations.snapshotForTask(taskId, environment);
        TaskEventRecord event = taskEventStore.append(
            taskId,
            "task.status.changed",
            Map.of("status", task.status().name(), "taskNo", task.taskNo()),
            now
        );
        taskEventPublisher.publish(event);
        return TaskView.from(task);
    }
}
