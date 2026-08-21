package com.atlas.enterprise.task.api;

import com.atlas.enterprise.api.TraceIdFilter;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.application.DataSnapshotService;
import com.atlas.enterprise.task.application.TaskStepApplicationService;
import com.atlas.enterprise.task.application.TaskWorkspaceApplicationService;
import com.atlas.enterprise.task.application.SubjectDataConflictResolutionApplicationService;
import com.atlas.enterprise.task.application.TaskWorkflowRunner;
import com.atlas.enterprise.operations.application.PlatformObservabilityService;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.application.OperatorConfirmationApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}")
public class TaskWorkflowController {
    private final TaskWorkflowRunner workflow;
    private final TaskStepApplicationService taskSteps;
    private final DataSnapshotService snapshots;
    private final OperatorConfirmationApplicationService operatorConfirmations;
    private final TaskWorkspaceApplicationService taskWorkspace;
    private final SubjectDataConflictResolutionApplicationService subjectConflictResolutions;
    private final PlatformObservabilityService observability;

    public TaskWorkflowController(
        TaskWorkflowRunner workflow,
        TaskStepApplicationService taskSteps,
        DataSnapshotService snapshots,
        OperatorConfirmationApplicationService operatorConfirmations,
        TaskWorkspaceApplicationService taskWorkspace,
        SubjectDataConflictResolutionApplicationService subjectConflictResolutions,
        PlatformObservabilityService observability
    ) {
        this.workflow = workflow;
        this.taskSteps = taskSteps;
        this.snapshots = snapshots;
        this.operatorConfirmations = operatorConfirmations;
        this.taskWorkspace = taskWorkspace;
        this.subjectConflictResolutions = subjectConflictResolutions;
        this.observability = observability;
    }

    @PostMapping("/execute")
    public TaskResponse execute(
        @PathVariable UUID taskId,
        @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId,
        @RequestHeader(value = "X-Worker-Id", defaultValue = "api-sync-worker") String workerId
    ) {
        return TaskResponse.from(workflow.run(taskId, traceId, workerId));
    }

    @PostMapping("/retry")
    public TaskResponse retry(
        @PathVariable UUID taskId,
        @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId,
        @RequestHeader(value = "X-Worker-Id", defaultValue = "api-sync-worker") String workerId,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String operatorId
    ) {
        TaskResponse response = TaskResponse.from(workflow.retry(taskId, traceId, workerId));
        observability.recordRetry(taskId, operatorId, traceId);
        return response;
    }

    @PostMapping("/subject-confirmation")
    public TaskResponse confirmSubject(
        @PathVariable UUID taskId,
        @Valid @RequestBody SubjectConfirmationRequest request,
        @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String operatorId,
        @RequestHeader(value = "X-Worker-Id", defaultValue = "api-subject-confirmation-worker")
            String workerId
    ) {
        workflow.confirmSubject(
            taskId,
            request.sourceSystem(),
            request.sourceEntityId(),
            traceId,
            operatorId
        );
        return TaskResponse.from(workflow.run(taskId, traceId, workerId));
    }

    @GetMapping("/steps")
    public List<TaskStepResponse> steps(@PathVariable UUID taskId) {
        return taskSteps.findByTaskId(taskId).stream()
            .map(TaskStepResponse::from)
            .toList();
    }

    @GetMapping("/snapshot")
    public DataSnapshot snapshot(@PathVariable UUID taskId) {
        return snapshots.latest(taskId);
    }

    @GetMapping("/workspace")
    public TaskWorkspaceResponse workspace(@PathVariable UUID taskId) {
        return TaskWorkspaceResponse.from(taskWorkspace.get(taskId));
    }

    @PostMapping("/subject-data-conflict-resolution")
    public TaskWorkspaceResponse resolveSubjectDataConflict(
        @PathVariable UUID taskId,
        @Valid @RequestBody SubjectDataConflictResolutionRequest request,
        @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator")
            String operatorId,
        @RequestHeader(
            value = "X-Worker-Id",
            defaultValue = "api-subject-data-conflict-worker"
        ) String workerId
    ) {
        subjectConflictResolutions.resolve(
            taskId,
            request.decision(),
            request.note(),
            operatorId
        );
        workflow.run(taskId, traceId, workerId);
        return TaskWorkspaceResponse.from(taskWorkspace.get(taskId));
    }

    @PostMapping("/operator-confirmation")
    public OperatorConfirmation confirmOperatorReview(
        @PathVariable UUID taskId,
        @Valid @RequestBody(required = false) OperatorConfirmationRequest request,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator")
            String operatorId
    ) {
        return operatorConfirmations.confirm(
            taskId,
            request == null ? null : request.note(),
            operatorId
        );
    }

    @GetMapping("/operator-confirmations")
    public List<OperatorConfirmation> operatorConfirmations(
        @PathVariable UUID taskId
    ) {
        return operatorConfirmations.list(taskId);
    }
}
