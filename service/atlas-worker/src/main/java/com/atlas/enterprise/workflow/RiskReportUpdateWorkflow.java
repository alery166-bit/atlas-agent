package com.atlas.enterprise.workflow;

import com.atlas.enterprise.company.AtlasCompanyIdentity;
import com.atlas.enterprise.company.CompanyCandidate;
import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.CompanyQuery;
import com.atlas.enterprise.company.CompanyResolution;
import com.atlas.enterprise.company.CompanyResolutionStatus;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceResult;
import com.atlas.enterprise.company.SourceStatus;
import com.atlas.enterprise.company.port.CompanyDataProvider;
import com.atlas.enterprise.company.port.CompanyIdentityRepository;
import com.atlas.enterprise.company.port.CompanyRefreshPort;
import com.atlas.enterprise.company.port.CompanyRefreshResult;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.configuration.application.SkillExecutionGate;
import com.atlas.enterprise.intelligence.PublicIntelligenceRun;
import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.intelligence.application.PublicIntelligenceApplicationService;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewJob;
import com.atlas.enterprise.intelligence.application.RequiredSearchProviderFailedException;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticReviewJobRunner;
import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.TaskStep;
import com.atlas.enterprise.task.TaskStepName;
import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.application.TaskView;
import com.atlas.enterprise.task.application.TaskWorkflowConflictException;
import com.atlas.enterprise.task.application.TaskWorkflowRunner;
import com.atlas.enterprise.task.application.AutonomousTaskCompletionService;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskExecutionLeaseRepository;
import com.atlas.enterprise.task.port.TaskRepository;
import com.atlas.enterprise.task.port.TaskStepRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiskReportUpdateWorkflow implements TaskWorkflowRunner {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final TaskStepName RESOLVE = TaskStepName.RESOLVE_SUBJECT;
    private static final TaskStepName COLLECT = TaskStepName.COLLECT_STRUCTURED_DATA;
    private static final TaskStepName SEARCH = TaskStepName.SEARCH_PUBLIC_INTELLIGENCE;

    private final TaskRepository tasks;
    private final TaskStepRepository steps;
    private final TaskExecutionLeaseRepository leases;
    private final CompanyDataProvider companyData;
    private final CompanyIdentityRepository identities;
    private final DataSnapshotRepository snapshots;
    private final PublicIntelligenceApplicationService publicIntelligence;
    private final EvidenceSemanticReviewJobRunner semanticReviewJobs;
    private final AutonomousTaskCompletionService autonomousCompletion;
    private final SkillExecutionGate skillGate;
    private final TaskEventStore eventStore;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;
    private CompanyRefreshPort companyRefresh;

    public RiskReportUpdateWorkflow(
        TaskRepository tasks,
        TaskStepRepository steps,
        TaskExecutionLeaseRepository leases,
        CompanyDataProvider companyData,
        CompanyIdentityRepository identities,
        DataSnapshotRepository snapshots,
        PublicIntelligenceApplicationService publicIntelligence,
        EvidenceSemanticReviewJobRunner semanticReviewJobs,
        AutonomousTaskCompletionService autonomousCompletion,
        SkillExecutionGate skillGate,
        TaskEventStore eventStore,
        TaskEventPublisher eventPublisher,
        Clock clock
    ) {
        this.tasks = tasks;
        this.steps = steps;
        this.leases = leases;
        this.companyData = companyData;
        this.identities = identities;
        this.snapshots = snapshots;
        this.publicIntelligence = publicIntelligence;
        this.semanticReviewJobs = semanticReviewJobs;
        this.autonomousCompletion = autonomousCompletion;
        this.skillGate = skillGate;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setCompanyRefresh(CompanyRefreshPort companyRefresh) {
        this.companyRefresh = companyRefresh;
    }

    @Override
    public TaskView run(UUID taskId, String traceId, String workerId) {
        Instant now = clock.instant();
        InvestigationTask task = load(taskId);
        skillGate.requireEnabled(taskId, "company.resolve");
        if (!leases.tryAcquire(taskId, workerId, now, LEASE_DURATION)) {
            throw new TaskWorkflowConflictException(taskId, "Task is already being executed");
        }
        try {
            boolean continueRunning = true;
            while (continueRunning) {
                task = load(taskId);
                continueRunning = switch (task.status()) {
                    case CREATED -> {
                        transition(task, TaskStatus.RESOLVING_SUBJECT, RESOLVE.name());
                        yield true;
                    }
                    case RESOLVING_SUBJECT -> resolveSubject(task, traceId);
                    // Historical tasks can still be restored in this retired
                    // status. They continue from the enterprise data snapshot.
                    case LOADING_PREVIOUS_REPORT -> {
                        transition(task, TaskStatus.COLLECTING_STRUCTURED_DATA, COLLECT.name());
                        yield true;
                    }
                    case COLLECTING_STRUCTURED_DATA -> collectStructuredData(task, traceId, workerId);
                    case SEARCHING_PUBLIC_INTELLIGENCE -> searchPublicIntelligence(
                        task,
                        traceId,
                        workerId
                    );
                    case CALCULATING_RISK, WAITING_SUBJECT_DATA_REVIEW ->
                        calculateRiskWhenReviewIsNotRequired(task);
                    default -> false;
                };
            }
            return TaskView.from(load(taskId));
        } finally {
            leases.release(taskId, workerId);
        }
    }

    @Override
    public TaskView retry(UUID taskId, String traceId, String workerId) {
        InvestigationTask task = load(taskId);
        if (task.status() != TaskStatus.SOURCE_FAILED
            || task.errorCode() == null
            || !task.errorCode().retryable()) {
            throw new TaskWorkflowConflictException(
                taskId,
                "Task is not in a retryable source failure state"
            );
        }
        TaskStatus retryStatus = switch (TaskStepName.valueOf(task.failedStep())) {
            case RESOLVE_SUBJECT -> TaskStatus.RESOLVING_SUBJECT;
            case VALIDATE_PREVIOUS_REPORT_REFERENCE -> TaskStatus.COLLECTING_STRUCTURED_DATA;
            case COLLECT_STRUCTURED_DATA -> TaskStatus.COLLECTING_STRUCTURED_DATA;
            case SEARCH_PUBLIC_INTELLIGENCE -> TaskStatus.SEARCHING_PUBLIC_INTELLIGENCE;
        };
        transition(task, retryStatus, task.failedStep());
        return run(taskId, traceId, workerId);
    }

    @Override
    public TaskView confirmSubject(
        UUID taskId,
        String sourceSystem,
        String sourceEntityId,
        String traceId,
        String operatorId
    ) {
        InvestigationTask task = load(taskId);
        if (task.status() != TaskStatus.WAITING_SUBJECT_CONFIRMATION) {
            throw new TaskWorkflowConflictException(taskId, "Task is not waiting for subject confirmation");
        }
        CompanyResolution resolution = companyData.resolve(new CompanyQuery(task.companyQuery()));
        CompanyCandidate selected = resolution.candidates().stream()
            .filter(candidate -> candidate.sourceSystem().equals(sourceSystem))
            .filter(candidate -> candidate.sourceEntityId().equals(sourceEntityId))
            .findFirst()
            .orElseThrow(() -> new TaskWorkflowConflictException(
                taskId,
                "Selected subject candidate is no longer available"
            ));
        Instant now = clock.instant();
        AtlasCompanyIdentity identity = identities.bind(selected.resolve(), now);
        task.bindCompany(identity.atlasCompanyId(), now);
        task.transitionTo(TaskStatus.RESOLVING_SUBJECT, RESOLVE.name(), now);
        tasks.save(task);
        publish(taskId, "task.status.changed", Map.of(
            "status", task.status().name(),
            "atlasCompanyId", identity.atlasCompanyId().toString(),
            "operatorId", operatorId
        ), now);
        return TaskView.from(task);
    }

    private boolean resolveSubject(InvestigationTask task, String traceId) {
        skillGate.requireEnabled(task.taskId(), "company.resolve");
        Instant now = clock.instant();
        TaskStep step = steps.start(
            task.taskId(),
            RESOLVE,
            ContentHash.value(task.companyQuery()),
            traceId,
            now
        );
        publishStepStarted(step, now);

        if (task.atlasCompanyId() != null) {
            steps.skip(step.taskStepId(), "company:" + task.atlasCompanyId(), now);
            publishStepCompleted(step, "company:" + task.atlasCompanyId(), now);
            transition(task, TaskStatus.COLLECTING_STRUCTURED_DATA, COLLECT.name());
            return true;
        }

        CompanyResolution resolution = companyData.resolve(new CompanyQuery(task.companyQuery()));
        if (resolution.status() == CompanyResolutionStatus.FAILED) {
            failSource(
                task,
                step,
                TaskErrorCode.STRUCTURED_SOURCE_UNAVAILABLE,
                firstFailureMessage(resolution.sourceStatuses())
            );
            return false;
        }
        if (resolution.status() == CompanyResolutionStatus.NOT_FOUND) {
            failSource(task, step, TaskErrorCode.SUBJECT_NOT_FOUND, "No company subject matched the query");
            return false;
        }
        if (resolution.status() == CompanyResolutionStatus.AMBIGUOUS) {
            String outputRef = "candidates:" + resolution.candidates().size();
            steps.complete(step.taskStepId(), outputRef, clock.instant());
            publishStepCompleted(step, outputRef, clock.instant());
            transition(task, TaskStatus.WAITING_SUBJECT_CONFIRMATION, RESOLVE.name());
            publish(task.taskId(), "operator.action.required", Map.of(
                "action", "CONFIRM_SUBJECT",
                "candidateCount", Integer.toString(resolution.candidates().size())
            ), clock.instant());
            return false;
        }

        AtlasCompanyIdentity identity = identities.bind(
            resolution.uniqueCandidate().resolve(),
            clock.instant()
        );
        task.bindCompany(identity.atlasCompanyId(), clock.instant());
        String outputRef = "company:" + identity.atlasCompanyId();
        steps.complete(step.taskStepId(), outputRef, clock.instant());
        publishStepCompleted(step, outputRef, clock.instant());
        transition(task, TaskStatus.COLLECTING_STRUCTURED_DATA, COLLECT.name());
        return true;
    }

    private boolean collectStructuredData(
        InvestigationTask task,
        String traceId,
        String workerId
    ) {
        skillGate.requireEnabled(task.taskId(), "company.snapshot");
        if (snapshots.findLatestByTaskId(task.taskId()).isPresent()) {
            transition(task, TaskStatus.SEARCHING_PUBLIC_INTELLIGENCE, "SEARCH_PUBLIC_INTELLIGENCE");
            return true;
        }

        AtlasCompanyIdentity identity = identities.findById(task.atlasCompanyId())
            .orElseThrow(() -> new TaskWorkflowConflictException(
                task.taskId(),
                "Confirmed company identity is missing"
            ));
        ResolvedCompany company = identity.resolvedCompany();
        Instant now = clock.instant();
        TaskStep step = steps.start(
            task.taskId(),
            COLLECT,
            ContentHash.value(company.sourceSystem() + ":" + company.sourceEntityId()),
            traceId,
            now
        );
        publishStepStarted(step, now);
        leases.heartbeat(task.taskId(), workerId, now, LEASE_DURATION);

        SourceResult<CompanyFacts> facts;
        SourceResult<CompanyChange> changes;
        SourceResult<RiskEvent> riskEvents;
        String refreshId = null;
        if (companyRefresh != null && companyRefresh.enabled()) {
            CompanyRefreshResult refreshed = companyRefresh.refresh(
                company,
                () -> leases.heartbeat(
                    task.taskId(),
                    workerId,
                    clock.instant(),
                    LEASE_DURATION
                )
            );
            facts = refreshed.facts();
            changes = refreshed.changes();
            riskEvents = refreshed.riskEvents();
            refreshId = refreshed.refreshId();
            publish(task.taskId(), "company.refresh.finished", Map.of(
                "refreshId", refreshed.refreshId(),
                "provider", refreshed.providerName(),
                "fetchedAt", refreshed.fetchedAt().toString(),
                "categoryStatuses", refreshStatusSummary(refreshed.categoryStatuses())
            ), clock.instant());
            if (refreshed.failed()) {
                failSource(
                    task,
                    step,
                    TaskErrorCode.STRUCTURED_SOURCE_QUERY_FAILED,
                    refreshed.firstFailureMessage()
                );
                return false;
            }
            leases.heartbeat(task.taskId(), workerId, clock.instant(), LEASE_DURATION);
        } else {
            facts = companyData.loadFacts(company);
            changes = companyData.loadChanges(company);
            riskEvents = companyData.loadRiskEvents(company);
        }
        List<SourceStatus> statuses = new ArrayList<>();
        statuses.addAll(facts.sourceStatuses());
        statuses.addAll(changes.sourceStatuses());
        statuses.addAll(riskEvents.sourceStatuses());

        if (facts.failed() || changes.failed() || riskEvents.failed() || facts.records().isEmpty()) {
            failSource(
                task,
                step,
                TaskErrorCode.STRUCTURED_SOURCE_QUERY_FAILED,
                firstFailureMessage(statuses)
            );
            return false;
        }

        CompanyFacts companyFacts = facts.records().getFirst();
        String contentHash = ContentHash.snapshot(
            companyFacts,
            changes.records(),
            riskEvents.records(),
            statuses
        );
        DataSnapshot snapshot = new DataSnapshot(
            UUID.randomUUID(),
            task.taskId(),
            identity.atlasCompanyId(),
            snapshots.nextVersion(task.taskId()),
            companyFacts,
            changes.records(),
            riskEvents.records(),
            statuses,
            contentHash,
            clock.instant()
        );
        snapshots.save(snapshot);

        String outputRef = "snapshot:" + snapshot.snapshotId();
        steps.complete(step.taskStepId(), outputRef, clock.instant());
        publishStepCompleted(step, outputRef, clock.instant());
        transition(task, TaskStatus.SEARCHING_PUBLIC_INTELLIGENCE, "SEARCH_PUBLIC_INTELLIGENCE");
        Map<String, String> snapshotPayload = new java.util.LinkedHashMap<>();
        snapshotPayload.put("snapshotId", snapshot.snapshotId().toString());
        snapshotPayload.put("contentHash", snapshot.contentHash());
        snapshotPayload.put("riskEventCount", Integer.toString(snapshot.riskEvents().size()));
        if (refreshId != null) {
            snapshotPayload.put("refreshId", refreshId);
            snapshotPayload.put("refreshProvider", "xlb-openapi");
        }
        publish(task.taskId(), "data.snapshot.frozen", snapshotPayload, clock.instant());
        return true;
    }

    private static String refreshStatusSummary(List<SourceStatus> statuses) {
        return statuses.stream()
            .map(status -> status.sourceName() + "=" + status.queryStatus()
                + "(" + status.recordCount() + ")")
            .reduce((left, right) -> left + ";" + right)
            .orElse("");
    }

    private boolean searchPublicIntelligence(
        InvestigationTask task,
        String traceId,
        String workerId
    ) {
        skillGate.requireEnabled(task.taskId(), "intelligence.search");
        DataSnapshot snapshot = snapshots.findLatestByTaskId(task.taskId())
            .orElseThrow(() -> new TaskWorkflowConflictException(
                task.taskId(),
                "Frozen data snapshot is missing before public search"
            ));
        Instant now = clock.instant();
        TaskStep step = steps.start(
            task.taskId(),
            SEARCH,
            ContentHash.value(snapshot.contentHash()),
            traceId,
            now
        );
        publishStepStarted(step, now);
        leases.heartbeat(task.taskId(), workerId, now, LEASE_DURATION);
        try {
            PublicIntelligenceRun result = publicIntelligence.search(task.taskId());
            String outputRef = "public-evidence:" + result.evidence().size();
            steps.complete(step.taskStepId(), outputRef, clock.instant());
            publishStepCompleted(step, outputRef, clock.instant());
            transition(task, TaskStatus.CALCULATING_RISK, "CALCULATE_RISK");
            publish(task.taskId(), "public.intelligence.collected", Map.of(
                "searchBatchCount", Integer.toString(result.searches().size()),
                "evidenceCount", Integer.toString(result.evidence().size()),
                "verificationStatus", "UNVERIFIED"
            ), clock.instant());
            return true;
        } catch (RequiredSearchProviderFailedException exception) {
            failSource(
                task,
                step,
                TaskErrorCode.SEARCH_PROVIDER_UNAVAILABLE,
                exception.getMessage()
            );
            return false;
        }
    }

    private boolean calculateRiskWhenReviewIsNotRequired(InvestigationTask task) {
        List<com.atlas.enterprise.intelligence.PublicEvidence> evidence =
            publicIntelligence.evidence(task.taskId());
        boolean hasUnverifiedEvidence = evidence.stream().anyMatch(item ->
            item.verificationStatus() == EvidenceVerificationStatus.UNVERIFIED
        );
        if (hasUnverifiedEvidence) {
            boolean hasPendingModelReview = evidence.stream().anyMatch(item ->
                item.verificationStatus() == EvidenceVerificationStatus.UNVERIFIED
                    && !item.metadata().containsKey("llm_reviewed_at")
            );
            if (hasPendingModelReview
                && publicIntelligence.semanticReviewAvailable(task.taskId())) {
                EvidenceSemanticReviewJob job = semanticReviewJobs.start(task.taskId());
                publish(task.taskId(), "public.intelligence.model.review.requested", Map.of(
                    "reviewJobId", job.reviewJobId().toString(),
                    "trigger", "AUTOMATIC_WORKFLOW"
                ), clock.instant());
                return false;
            }
            publish(task.taskId(), "operator.action.required", Map.of(
                "action", "REVIEW_EVIDENCE",
                "reason", hasPendingModelReview
                    ? "SEMANTIC_MODEL_UNAVAILABLE"
                    : "MODEL_UNCERTAIN_OR_POLICY_BLOCKED"
            ), clock.instant());
            return false;
        }
        autonomousCompletion.completeIfReady(task.taskId());
        return false;
    }

    private void failSource(
        InvestigationTask task,
        TaskStep step,
        TaskErrorCode errorCode,
        String message
    ) {
        Instant now = clock.instant();
        steps.fail(step.taskStepId(), errorCode, message, now);
        publish(task.taskId(), "step.failed", Map.of(
            "step", step.stepName().name(),
            "attemptNo", Integer.toString(step.attemptNo()),
            "errorCode", errorCode.name(),
            "message", message == null ? "" : message
        ), now);
        task.fail(TaskStatus.SOURCE_FAILED, step.stepName().name(), errorCode, now);
        tasks.save(task);
        publish(task.taskId(), "task.status.changed", Map.of(
            "status", task.status().name(),
            "failedStep", step.stepName().name(),
            "errorCode", errorCode.name()
        ), now);
    }

    private void transition(
        InvestigationTask task,
        TaskStatus target,
        String currentStep
    ) {
        Instant now = clock.instant();
        task.transitionTo(target, currentStep, now);
        tasks.save(task);
        publish(task.taskId(), "task.status.changed", Map.of(
            "status", target.name(),
            "currentStep", currentStep
        ), now);
    }

    private void publishStepStarted(TaskStep step, Instant occurredAt) {
        publish(step.taskId(), "step.started", Map.of(
            "step", step.stepName().name(),
            "attemptNo", Integer.toString(step.attemptNo())
        ), occurredAt);
    }

    private void publishStepCompleted(TaskStep step, String outputRef, Instant occurredAt) {
        publish(step.taskId(), "step.completed", Map.of(
            "step", step.stepName().name(),
            "attemptNo", Integer.toString(step.attemptNo()),
            "outputRef", outputRef
        ), occurredAt);
    }

    private void publish(
        UUID taskId,
        String type,
        Map<String, String> payload,
        Instant occurredAt
    ) {
        TaskEventRecord event = eventStore.append(taskId, type, payload, occurredAt);
        eventPublisher.publish(event);
    }

    private InvestigationTask load(UUID taskId) {
        return tasks.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static String firstFailureMessage(List<SourceStatus> statuses) {
        return statuses.stream()
            .filter(SourceStatus::failed)
            .map(SourceStatus::message)
            .filter(message -> message != null && !message.isBlank())
            .findFirst()
            .orElse("Required structured source query failed");
    }
}
