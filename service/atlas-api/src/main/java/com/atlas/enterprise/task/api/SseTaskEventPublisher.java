package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.application.TaskEventRecord;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseTaskEventPublisher {
    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters =
        new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        CopyOnWriteArrayList<SseEmitter> taskEmitters =
            emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>());
        taskEmitters.add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));

        return emitter;
    }

    public void replay(UUID taskId, SseEmitter emitter, List<TaskEventRecord> events) {
        events.forEach(event -> send(taskId, emitter, event));
    }

    public void publish(TaskEventRecord event) {
        emitters.getOrDefault(event.taskId(), new CopyOnWriteArrayList<>())
            .forEach(emitter -> send(event.taskId(), emitter, event));
    }

    private void send(UUID taskId, SseEmitter emitter, TaskEventRecord event) {
        try {
            emitter.send(SseEmitter.event()
                .id(Long.toString(event.eventId()))
                .name(event.type())
                .data(event));
        } catch (IOException exception) {
            remove(taskId, emitter);
        }
    }

    private void remove(UUID taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters == null) {
            return;
        }
        taskEmitters.remove(emitter);
        if (taskEmitters.isEmpty()) {
            emitters.remove(taskId, taskEmitters);
        }
    }
}
