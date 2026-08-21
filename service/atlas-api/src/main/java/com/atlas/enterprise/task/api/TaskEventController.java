package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.application.TaskApplicationService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tasks")
public class TaskEventController {
    private final SseTaskEventPublisher publisher;
    private final TaskApplicationService tasks;

    public TaskEventController(SseTaskEventPublisher publisher, TaskApplicationService tasks) {
        this.publisher = publisher;
        this.tasks = tasks;
    }

    @GetMapping(path = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @PathVariable UUID taskId,
        @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId
    ) {
        SseEmitter emitter = publisher.subscribe(taskId);
        publisher.replay(taskId, emitter, tasks.eventsAfter(taskId, lastEventId));
        return emitter;
    }
}
