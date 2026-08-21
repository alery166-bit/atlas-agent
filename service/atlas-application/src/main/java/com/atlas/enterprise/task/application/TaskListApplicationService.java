package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.intelligence.EvidenceContentReference;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.report.port.ReportVersionRepository;
import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.port.OperatorDecisionRepository;
import com.atlas.enterprise.risk.port.RiskScoreSnapshotRepository;
import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.OperatorReviewState;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.port.OperatorConfirmationRepository;
import com.atlas.enterprise.task.port.SubjectDataConflictResolutionRepository;
import com.atlas.enterprise.task.port.TaskRepository;
import com.atlas.enterprise.task.port.TaskSearchCriteria;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskListApplicationService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 256;
    private static final int MAX_OPERATOR_ID_LENGTH = 64;

    private final TaskRepository tasks;
    private final DataSnapshotRepository snapshots;
    private final RiskScoreSnapshotRepository scores;
    private final OperatorDecisionRepository decisions;
    private final PublicIntelligenceRepository publicIntelligence;
    private final OperatorConfirmationRepository confirmations;
    private final SubjectDataConflictResolutionRepository conflictResolutions;
    private final ReportVersionRepository reports;
    private final OperatorReviewStateService reviewStates;

    public TaskListApplicationService(
        TaskRepository tasks,
        DataSnapshotRepository snapshots,
        RiskScoreSnapshotRepository scores,
        OperatorDecisionRepository decisions,
        PublicIntelligenceRepository publicIntelligence,
        OperatorConfirmationRepository confirmations,
        SubjectDataConflictResolutionRepository conflictResolutions,
        ReportVersionRepository reports,
        OperatorReviewStateService reviewStates
    ) {
        this.tasks = tasks;
        this.snapshots = snapshots;
        this.scores = scores;
        this.decisions = decisions;
        this.publicIntelligence = publicIntelligence;
        this.confirmations = confirmations;
        this.conflictResolutions = conflictResolutions;
        this.reports = reports;
        this.reviewStates = reviewStates;
    }

    @Transactional(readOnly = true)
    public TaskListPageView list(TaskListQuery query) {
        ValidatedQuery validated = validate(query);
        Cursor cursor = decodeCursor(validated.cursor());
        List<InvestigationTask> found = tasks.search(new TaskSearchCriteria(
            validated.query(),
            validated.statuses(),
            validated.operatorId(),
            cursor == null ? null : cursor.updatedAt(),
            cursor == null ? null : cursor.taskId(),
            validated.pageSize() + 1
        ));
        boolean hasMore = found.size() > validated.pageSize();
        List<InvestigationTask> page = hasMore
            ? found.subList(0, validated.pageSize())
            : found;
        if (page.isEmpty()) {
            return new TaskListPageView(
                List.of(),
                null,
                false,
                validated.pageSize()
            );
        }

        List<UUID> taskIds = page.stream()
            .map(InvestigationTask::taskId)
            .toList();
        Map<UUID, DataSnapshot> snapshotByTask =
            snapshots.findLatestByTaskIds(taskIds);
        Map<UUID, RiskScoreSnapshot> scoreByTask =
            scores.findLatestByTaskIds(taskIds);
        Map<UUID, List<OperatorDecision>> decisionsByTask = groupByTask(
            decisions.findByTaskIds(taskIds),
            OperatorDecision::taskId
        );
        Map<UUID, List<PublicEvidence>> evidenceByTask = groupByTask(
            publicIntelligence.findEvidenceByTaskIds(taskIds),
            PublicEvidence::taskId
        );
        Map<UUID, List<EvidenceContentReference>> contentByTask = groupByTask(
            publicIntelligence.findContentReferencesByTaskIds(taskIds),
            EvidenceContentReference::taskId
        );
        Map<UUID, OperatorConfirmation> confirmationByTask =
            confirmations.findLatestByTaskIds(taskIds);
        var conflictResolutionByTask =
            conflictResolutions.findLatestByTaskIds(taskIds);
        Map<UUID, ReportVersion> reportByTask =
            reports.findLatestByTaskIds(taskIds);

        List<TaskListPageView.Item> items = new ArrayList<>(page.size());
        for (InvestigationTask task : page) {
            UUID taskId = task.taskId();
            DataSnapshot snapshot = snapshotByTask.get(taskId);
            RiskScoreSnapshot score = scoreByTask.get(taskId);
            List<PublicEvidence> evidence =
                evidenceByTask.getOrDefault(taskId, List.of());
            List<EvidenceContentReference> content =
                contentByTask.getOrDefault(taskId, List.of());
            TaskWorkspaceView.EvidenceProgress progress =
                TaskReadinessEvaluator.evidenceProgress(evidence, content);
            var conflictResolution = conflictResolutionByTask.get(taskId);
            List<TaskWorkspaceView.ReadinessBlocker> blockers =
                TaskReadinessEvaluator.readinessBlockers(
                    snapshot,
                    score,
                    progress,
                    snapshot != null
                        && conflictResolution != null
                        && conflictResolution.matches(snapshot.snapshotId())
                );
            OperatorReviewState currentState =
                TaskReadinessEvaluator.hasInputBlocker(blockers)
                    ? null
                    : reviewStates.build(
                        snapshot,
                        score,
                        decisionsByTask.getOrDefault(taskId, List.of()),
                        evidence,
                        content
                    );
            OperatorConfirmation confirmation =
                confirmationByTask.get(taskId);
            TaskWorkspaceView.ConfirmationState confirmationState =
                TaskReadinessEvaluator.confirmationState(
                    confirmation,
                    currentState
                );
            ReportVersion report = reportByTask.get(taskId);
            boolean hasCurrentGeneratedReport =
                ReportReadinessEvaluator.isCurrentGeneratedReport(
                    report,
                    snapshot,
                    score,
                    confirmation,
                    confirmationState
                );
            items.add(new TaskListPageView.Item(
                TaskView.from(task),
                snapshot == null
                    ? task.companyQuery()
                    : snapshot.companyFacts().canonicalName(),
                riskSummary(score),
                progress,
                confirmationState,
                blockers,
                TaskActionResolver.resolve(
                    task.status(),
                    blockers,
                    confirmationState,
                    hasCurrentGeneratedReport
                ),
                confirmationSummary(confirmation),
                reportSummary(report)
            ));
        }

        InvestigationTask last = page.get(page.size() - 1);
        return new TaskListPageView(
            items,
            hasMore ? encodeCursor(last.updatedAt(), last.taskId()) : null,
            hasMore,
            validated.pageSize()
        );
    }

    private static TaskListPageView.RiskSummary riskSummary(
        RiskScoreSnapshot score
    ) {
        return score == null ? null : new TaskListPageView.RiskSummary(
            score.scoreSnapshotId(),
            score.originalScore(),
            score.manualScore(),
            score.originalRiskLevel(),
            score.manualRiskLevel(),
            score.calculatedAt()
        );
    }

    private static TaskListPageView.ConfirmationSummary confirmationSummary(
        OperatorConfirmation confirmation
    ) {
        return confirmation == null
            ? null
            : new TaskListPageView.ConfirmationSummary(
                confirmation.confirmationId(),
                confirmation.operatorId(),
                confirmation.confirmedEvidenceCount(),
                confirmation.rejectedEvidenceCount(),
                confirmation.confirmedAt()
            );
    }

    private static TaskListPageView.ReportSummary reportSummary(
        ReportVersion report
    ) {
        return report == null ? null : new TaskListPageView.ReportSummary(
            report.reportId(),
            report.reportVersionNo(),
            report.status(),
            report.templateVersion(),
            report.generatedAt()
        );
    }

    private static <T> Map<UUID, List<T>> groupByTask(
        List<T> values,
        Function<T, UUID> taskId
    ) {
        return values.stream().collect(Collectors.groupingBy(taskId));
    }

    private static ValidatedQuery validate(TaskListQuery query) {
        if (query.pageSize() < 1 || query.pageSize() > MAX_PAGE_SIZE) {
            throw new TaskListValidationException(
                "page_size must be between 1 and " + MAX_PAGE_SIZE
            );
        }
        String search = normalize(query.query());
        if (search != null && search.length() > MAX_QUERY_LENGTH) {
            throw new TaskListValidationException(
                "query must not exceed " + MAX_QUERY_LENGTH + " characters"
            );
        }
        String operatorId = normalize(query.operatorId());
        if (operatorId != null && operatorId.length() > MAX_OPERATOR_ID_LENGTH) {
            throw new TaskListValidationException(
                "operator_id must not exceed "
                    + MAX_OPERATOR_ID_LENGTH
                    + " characters"
            );
        }
        return new ValidatedQuery(
            search,
            query.statuses(),
            operatorId,
            query.pageSize(),
            normalize(query.cursor())
        );
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String encodeCursor(Instant updatedAt, UUID taskId) {
        String raw = updatedAt + "|" + taskId;
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            String raw = new String(
                Base64.getUrlDecoder().decode(encoded),
                StandardCharsets.UTF_8
            );
            String[] values = raw.split("\\|", 2);
            if (values.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(
                Instant.parse(values[0]),
                UUID.fromString(values[1])
            );
        } catch (
            IllegalArgumentException
            | DateTimeParseException exception
        ) {
            throw new TaskListValidationException("cursor is invalid");
        }
    }

    private record ValidatedQuery(
        String query,
        Set<TaskStatus> statuses,
        String operatorId,
        int pageSize,
        String cursor
    ) {
    }

    private record Cursor(Instant updatedAt, UUID taskId) {
    }
}
