package com.atlas.enterprise.task.application;

import com.atlas.enterprise.task.TaskStep;
import com.atlas.enterprise.task.port.TaskRepository;
import com.atlas.enterprise.task.port.TaskStepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskStepApplicationService {
    private final TaskRepository tasks;
    private final TaskStepRepository steps;

    public TaskStepApplicationService(TaskRepository tasks, TaskStepRepository steps) {
        this.tasks = tasks;
        this.steps = steps;
    }

    @Transactional(readOnly = true)
    public List<TaskStep> findByTaskId(UUID taskId) {
        if (tasks.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        return steps.findByTaskId(taskId);
    }
}
