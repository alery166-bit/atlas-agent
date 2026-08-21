package com.atlas.enterprise.task.api;

import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.application.CreateTaskCommand;
import com.atlas.enterprise.task.application.TaskApplicationService;
import com.atlas.enterprise.task.application.TaskListApplicationService;
import com.atlas.enterprise.task.application.TaskListQuery;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskApplicationService tasks;
    private final TaskListApplicationService taskList;

    public TaskController(
        TaskApplicationService tasks,
        TaskListApplicationService taskList
    ) {
        this.tasks = tasks;
        this.taskList = taskList;
    }

    @GetMapping
    public TaskListPageResponse list(
        @RequestParam(required = false) String query,
        @RequestParam(required = false) List<TaskStatus> status,
        @RequestParam(name = "operator_id", required = false) String operatorId,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
        @RequestParam(required = false) String cursor
    ) {
        return TaskListPageResponse.from(taskList.list(new TaskListQuery(
            query,
            status == null ? Set.of() : new HashSet<>(status),
            operatorId,
            pageSize,
            cursor
        )));
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(
        @Valid @RequestBody CreateTaskRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String operatorId
    ) {
        TaskResponse response = TaskResponse.from(tasks.create(new CreateTaskCommand(
            request.prompt(),
            request.companyQuery(),
            request.previousReportFileId(),
            operatorId,
            idempotencyKey
        )));
        return ResponseEntity.accepted()
            .location(URI.create("/api/tasks/" + response.taskId()))
            .body(response);
    }

    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable UUID taskId) {
        return TaskResponse.from(tasks.get(taskId));
    }
}
