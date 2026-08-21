package com.atlas.enterprise.report.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.application.SnapshotNotFoundException;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.configuration.application.SkillExecutionGate;
import com.atlas.enterprise.intelligence.EvidenceContentSnapshot;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.ReportDiff;
import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.ReportEvidenceItem;
import com.atlas.enterprise.report.ReportGenerationData;
import com.atlas.enterprise.report.ReportFieldChange;
import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.report.StoredReportObject;
import com.atlas.enterprise.report.port.PreviousReportParser;
import com.atlas.enterprise.report.port.ReportDocumentSource;
import com.atlas.enterprise.report.port.ReportStorage;
import com.atlas.enterprise.report.port.ReportTemplateRenderer;
import com.atlas.enterprise.report.port.ReportVersionRepository;
import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskRuleHit;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.application.RiskScoreNotFoundException;
import com.atlas.enterprise.risk.port.OperatorDecisionRepository;
import com.atlas.enterprise.risk.port.RiskScoreSnapshotRepository;
import com.atlas.enterprise.task.InvestigationTask;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.OperatorReviewState;
import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.application.OperatorReviewStateService;
import com.atlas.enterprise.task.application.ReportReadinessEvaluator;
import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.application.TaskWorkflowConflictException;
import com.atlas.enterprise.task.application.TaskWorkspaceView;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.OperatorConfirmationRepository;
import com.atlas.enterprise.task.port.TaskRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportApplicationService {
    private static final String DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ReportVersionRepository reports;
    private final ReportDocumentSource documents;
    private final PreviousReportParser previousReportParser;
    private final ReportTemplateRenderer renderer;
    private final ReportStorage storage;
    private final DataSnapshotRepository snapshots;
    private final RiskScoreSnapshotRepository scores;
    private final OperatorDecisionRepository decisions;
    private final PublicIntelligenceRepository publicIntelligence;
    private final OperatorConfirmationRepository confirmations;
    private final OperatorReviewStateService reviewStates;
    private final TaskRepository tasks;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;
    private final SkillExecutionGate skillGate;

    public ReportApplicationService(
        ReportVersionRepository reports,
        ReportDocumentSource documents,
        PreviousReportParser previousReportParser,
        ReportTemplateRenderer renderer,
        ReportStorage storage,
        DataSnapshotRepository snapshots,
        RiskScoreSnapshotRepository scores,
        OperatorDecisionRepository decisions,
        PublicIntelligenceRepository publicIntelligence,
        OperatorConfirmationRepository confirmations,
        OperatorReviewStateService reviewStates,
        TaskRepository tasks,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        SkillExecutionGate skillGate,
        Clock clock
    ) {
        this.reports = reports;
        this.documents = documents;
        this.previousReportParser = previousReportParser;
        this.renderer = renderer;
        this.storage = storage;
        this.snapshots = snapshots;
        this.scores = scores;
        this.decisions = decisions;
        this.publicIntelligence = publicIntelligence;
        this.confirmations = confirmations;
        this.reviewStates = reviewStates;
        this.tasks = tasks;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.skillGate = skillGate;
        this.clock = clock;
    }

    public ReportVersion generate(UUID taskId, String operatorId) {
        InvestigationTask task = requireTask(taskId);
        skillGate.requireEnabled(taskId, "report.generate");
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        RiskScoreSnapshot score = scores.findLatestByTaskId(taskId)
            .orElseThrow(() -> new RiskScoreNotFoundException(
                "No risk score has been calculated for task " + taskId,
                taskId
            ));
        if (!score.dataSnapshotId().equals(snapshot.snapshotId())) {
            throw new ReportValidationException(
                "Latest risk score was not calculated from the latest frozen data snapshot"
            );
        }
        if (snapshot.sourceStatuses().stream().anyMatch(status -> status.failed())) {
            throw new ReportValidationException(
                "Formal report cannot be generated while a required source has failed"
            );
        }

        ReportDocument template = documents.loadTemplate(taskId);
        PreviousReport templateBaseline = previousReportParser.parse(template.content());
        if (!templateBaseline.supportedForUpdate()) {
            throw new ReportValidationException(
                "Published report template is not supported for direct generation: "
                    + String.join("; ", templateBaseline.parseWarnings())
            );
        }
        List<OperatorDecision> taskDecisions = decisions.findByTaskId(taskId);
        List<ReportEvidenceItem> confirmedEvidence = confirmedEvidence(taskId);
        OperatorReviewState reviewState = reviewStates.current(taskId);
        OperatorConfirmation confirmation = confirmations.findLatestByTaskId(taskId)
            .filter(candidate -> candidate.matches(reviewState))
            .orElseThrow(() -> new ReportValidationException(
                "Operator confirmation is missing or stale; "
                    + "complete operator confirmation again"
            ));
        String inputHash = inputHash(
            template,
            snapshot,
            score,
            taskDecisions,
            confirmedEvidence,
            confirmation
        );
        var existing = reports.findByInputHash(taskId, inputHash);
        if (existing.isPresent() && existing.get().status() == ReportStatus.GENERATED) {
            return existing.get();
        }
        ReportStatus existingStatus = existing.map(ReportVersion::status).orElse(null);
        validateTaskState(task, existingStatus);

        LocalDate reportDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        ReportDiff diff = buildInitialDiff(snapshot, score, reportDate);
        ReportVersion report = existing
            .filter(candidate -> candidate.status() == ReportStatus.FAILED)
            .map(ReportVersion::retrying)
            .orElseGet(() -> new ReportVersion(
                UUID.randomUUID(),
                taskId,
                snapshot.atlasCompanyId(),
                template.templateVersion(),
                reports.nextVersion(taskId),
                ReportStatus.GENERATING,
                null,
                null,
                inputHash,
                null,
                null,
                null,
                snapshot.snapshotId(),
                score.scoreSnapshotId(),
                confirmation.confirmationId(),
                null,
                diff,
                null,
                null,
                requireOperator(operatorId)
            ));

        boolean regeneratingCompletedTask = task.status() == TaskStatus.COMPLETED;
        if (!regeneratingCompletedTask
            && task.status() != TaskStatus.GENERATING_REPORT) {
            task.transitionTo(TaskStatus.GENERATING_REPORT, "GENERATING_REPORT", clock.instant());
            tasks.save(task);
        }
        reports.save(report);
        emit(taskId, "report.generating", Map.of(
            "reportId", report.reportId().toString(),
            "version", report.reportVersionNo()
        ));

        try {
            byte[] content = renderer.render(
                template,
                new ReportGenerationData(
                    snapshot,
                    score,
                    templateBaseline,
                    reportDate,
                    taskDecisions,
                    confirmedEvidence
                )
            );
            validateGenerated(content);
            StoredReportObject stored = storage.put(report.reportId(), content);
            ReportVersion completed = report.generated(stored, clock.instant());
            reports.save(completed);
            if (!regeneratingCompletedTask) {
                task.transitionTo(TaskStatus.COMPLETED, "COMPLETED", clock.instant());
                tasks.save(task);
            }
            emit(taskId, "report.generated", Map.of(
                "reportId", completed.reportId().toString(),
                "version", completed.reportVersionNo(),
                "contentHash", completed.contentHash()
            ));
            return completed;
        } catch (RuntimeException exception) {
            ReportVersion failed = report.failed(safeFailure(exception), clock.instant());
            reports.save(failed);
            if (!regeneratingCompletedTask) {
                task.fail(
                    TaskStatus.REPORT_FAILED,
                    "GENERATING_REPORT",
                    TaskErrorCode.REPORT_GENERATION_FAILED,
                    clock.instant()
                );
                tasks.save(task);
            }
            emit(taskId, "report.failed", Map.of(
                "reportId", report.reportId().toString(),
                "retryable", true
            ));
            throw new ReportValidationException("DOCX report generation failed", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<ReportVersion> list(UUID taskId) {
        requireTask(taskId);
        return reports.findByTaskId(taskId);
    }

    @Transactional(readOnly = true)
    public ReportDiff diff(UUID taskId, UUID reportId) {
        ReportVersion current = requireOwnedReport(taskId, reportId);
        ReportDiff currentDiff = current.diff();
        if (currentDiff == null) {
            throw new ReportValidationException("Report comparison metadata is unavailable");
        }
        ReportVersion previous = reports.findByTaskId(taskId).stream()
            .filter(candidate -> candidate.status() == ReportStatus.GENERATED)
            .filter(candidate -> candidate.reportVersionNo() < current.reportVersionNo())
            .max(Comparator.comparingInt(ReportVersion::reportVersionNo))
            .orElse(null);
        ReportDiff previousDiff = previous == null ? null : previous.diff();
        DataSnapshot currentSnapshot = snapshots.findById(current.dataSnapshotId())
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        DataSnapshot previousSnapshot = previous == null
            ? null
            : snapshots.findById(previous.dataSnapshotId()).orElse(null);
        RiskScoreSnapshot currentScore = scores.findById(current.scoreSnapshotId())
            .orElseThrow(() -> new RiskScoreNotFoundException(
                "Report score snapshot is unavailable", taskId
            ));
        RiskScoreSnapshot previousScore = previous == null
            ? null
            : scores.findById(previous.scoreSnapshotId()).orElse(null);
        List<ReportFieldChange> changes = new ArrayList<>();
        addChange(changes, "报告版本", previous == null ? null : "V" + previous.reportVersionNo(), "V" + current.reportVersionNo());
        addChange(changes, "报告日期", previousDiff == null ? null : previousDiff.currentReportDate(), currentDiff.currentReportDate());
        addChange(changes, "原始风险分", previousDiff == null ? null : previousDiff.originalRiskScore(), currentDiff.originalRiskScore());
        addChange(changes, "人工风险分", previousDiff == null ? null : previousDiff.manualRiskScore(), currentDiff.manualRiskScore());
        addChange(changes, "风险事项数", previousDiff == null ? null : Integer.toString(previousDiff.currentRiskEventCount()), Integer.toString(currentDiff.currentRiskEventCount()));
        addChange(changes, "报告模板", previous == null ? null : previous.templateVersion(), current.templateVersion());
        List<ReportFieldChange> sectionChanges = new ArrayList<>();
        addChange(sectionChanges, "企业概况 / 企业名称", fact(previousSnapshot, "name"), fact(currentSnapshot, "name"));
        addChange(sectionChanges, "企业概况 / 统一社会信用代码", fact(previousSnapshot, "creditCode"), fact(currentSnapshot, "creditCode"));
        addChange(sectionChanges, "企业概况 / 法定代表人", fact(previousSnapshot, "legalRepresentative"), fact(currentSnapshot, "legalRepresentative"));
        addChange(sectionChanges, "企业概况 / 登记状态", fact(previousSnapshot, "registrationStatus"), fact(currentSnapshot, "registrationStatus"));
        addChange(sectionChanges, "企业概况 / 注册地址", fact(previousSnapshot, "registeredAddress"), fact(currentSnapshot, "registeredAddress"));

        List<ReportFieldChange> tableRowChanges = new ArrayList<>();
        addChange(tableRowChanges, "工商变更表 / 行数",
            previousSnapshot == null ? null : Integer.toString(previousSnapshot.companyChanges().size()),
            Integer.toString(currentSnapshot.companyChanges().size()));
        addChange(tableRowChanges, "结构化风险事项表 / 行数",
            previousSnapshot == null ? null : Integer.toString(previousSnapshot.riskEvents().size()),
            Integer.toString(currentSnapshot.riskEvents().size()));
        addChange(tableRowChanges, "评分依据 / 规则命中数",
            previousScore == null ? null : Integer.toString(previousScore.ruleHits().size()),
            Integer.toString(currentScore.ruleHits().size()));
        appendRuleHitChanges(tableRowChanges, previousScore, currentScore);

        List<ReportFieldChange> conclusionChanges = new ArrayList<>();
        addChange(conclusionChanges, "风险结论 / 原始风险等级",
            previousScore == null ? null : previousScore.originalRiskLevel().name(),
            currentScore.originalRiskLevel().name());
        addChange(conclusionChanges, "风险结论 / 最终风险等级",
            previousScore == null ? null : previousScore.manualRiskLevel().name(),
            currentScore.manualRiskLevel().name());
        addChange(conclusionChanges, "风险结论 / 最终风险分",
            previousScore == null ? null : decimal(previousScore.manualScore()),
            decimal(currentScore.manualScore()));
        long changedItems = java.util.stream.Stream.of(
                changes, sectionChanges, tableRowChanges, conclusionChanges
            )
            .flatMap(List::stream)
            .filter(change -> !java.util.Objects.equals(
                change.beforeValue(), change.afterValue()
            ))
            .count();
        return new ReportDiff(
            changes,
            previousDiff == null ? null : previousDiff.currentReportDate(),
            currentDiff.currentReportDate(),
            previousDiff == null ? null : previousDiff.manualRiskScore(),
            currentDiff.originalRiskScore(),
            currentDiff.manualRiskScore(),
            currentDiff.currentRiskEventCount(),
            previous == null
                ? "这是该任务的首个报告版本，没有上一版本可比较。"
                : "已比较 V%d 与 V%d，共识别 %d 项正文或元数据变化。".formatted(previous.reportVersionNo(), current.reportVersionNo(), changedItems),
            previous == null ? null : previous.reportVersionNo(),
            current.reportVersionNo(),
            previousDiff == null ? null : previousDiff.originalRiskScore(),
            previousDiff == null ? null : previousDiff.manualRiskScore(),
            previousDiff == null ? null : previousDiff.currentRiskEventCount(),
            previous == null ? null : previous.templateVersion(),
            current.templateVersion(),
            sectionChanges,
            tableRowChanges,
            conclusionChanges
        );
    }

    @Transactional(readOnly = true)
    public ReportDownload download(UUID taskId, UUID reportId) {
        ReportVersion report = requireOwnedReport(taskId, reportId);
        if (report.status() != ReportStatus.GENERATED
            || report.generatedReportUri() == null) {
            throw new ReportValidationException("Report is not available for download");
        }
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        RiskScoreSnapshot score = scores.findLatestByTaskId(taskId)
            .orElseThrow(() -> new RiskScoreNotFoundException(
                "No risk score has been calculated for task " + taskId,
                taskId
            ));
        OperatorConfirmation confirmation = confirmations
            .findLatestByTaskId(taskId)
            .orElse(null);
        OperatorReviewState reviewState = reviewStates.current(taskId);
        TaskWorkspaceView.ConfirmationState confirmationState =
            confirmation == null
                ? TaskWorkspaceView.ConfirmationState.PENDING
                : confirmation.matches(reviewState)
                    ? TaskWorkspaceView.ConfirmationState.VALID
                    : TaskWorkspaceView.ConfirmationState.STALE;
        if (!ReportReadinessEvaluator.isCurrentGeneratedReport(
            report,
            snapshot,
            score,
            confirmation,
            confirmationState
        )) {
            throw new ReportValidationException(
                "Report is historical or stale; confirm the latest review "
                    + "and generate a new formal report"
            );
        }
        String companyName = snapshot.companyFacts().canonicalName();
        String filename = "%s_企业风险监测分析报告_%s_V%d.docx".formatted(
            safeFilename(companyName),
            report.generatedAt().atZone(ZoneOffset.UTC).toLocalDate().format(COMPACT_DATE),
            report.reportVersionNo()
        );
        return new ReportDownload(report, filename, storage.get(report.generatedReportUri()));
    }

    @Transactional(readOnly = true)
    public ReportDownload downloadLatest(UUID taskId) {
        ReportVersion report = reports.findByTaskId(taskId).stream()
            .filter(candidate -> candidate.status() == ReportStatus.GENERATED)
            .findFirst()
            .orElseThrow(() -> new ReportValidationException(
                "No generated report is available for download"
            ));
        return download(taskId, report.reportId());
    }

    private InvestigationTask requireTask(UUID taskId) {
        return tasks.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private ReportVersion requireOwnedReport(UUID taskId, UUID reportId) {
        requireTask(taskId);
        ReportVersion report = reports.findById(reportId)
            .orElseThrow(() -> new ReportNotFoundException(reportId));
        if (!report.taskId().equals(taskId)) {
            throw new ReportNotFoundException(reportId);
        }
        return report;
    }

    private static void validateTaskState(
        InvestigationTask task,
        ReportStatus existingStatus
    ) {
        if (!canStartGeneration(task.status(), existingStatus)) {
            throw new TaskWorkflowConflictException(
                task.taskId(),
                "Report generation requires WAITING_OPERATOR_CONFIRMATION, REPORT_FAILED or COMPLETED"
            );
        }
    }

    static boolean canStartGeneration(
        TaskStatus taskStatus,
        ReportStatus existingStatus
    ) {
        if (taskStatus == TaskStatus.WAITING_OPERATOR_CONFIRMATION
            || taskStatus == TaskStatus.REPORT_FAILED
            || taskStatus == TaskStatus.COMPLETED) {
            return true;
        }
        return taskStatus == TaskStatus.GENERATING_REPORT
            && existingStatus == ReportStatus.FAILED;
    }

    private String inputHash(
        ReportDocument template,
        DataSnapshot snapshot,
        RiskScoreSnapshot score,
        List<OperatorDecision> taskDecisions,
        List<ReportEvidenceItem> confirmedEvidence,
        OperatorConfirmation confirmation
    ) {
        StringBuilder canonical = new StringBuilder()
            .append(template.templateVersion()).append('|')
            .append(template.contentHash()).append('|')
            .append(snapshot.contentHash()).append('|')
            .append(score.scoreSnapshotId()).append('|')
            .append(score.inputHash()).append('|')
            .append(score.originalScore().toPlainString()).append('|')
            .append(score.manualScore().toPlainString()).append('|')
            .append(confirmation.confirmationId()).append('|')
            .append(confirmation.reviewStateHash()).append('|')
            .append(renderer.rendererVersion());
        taskDecisions.stream()
            .sorted(Comparator.comparing(OperatorDecision::decisionId))
            .forEach(decision -> canonical
                .append('|').append(decision.decisionId())
                .append('|').append(decision.afterJson())
                .append('|').append(decision.reasonCode())
                .append('|').append(decision.reasonText()));
        confirmedEvidence.stream()
            .sorted(Comparator.comparing(ReportEvidenceItem::evidenceId))
            .forEach(evidence -> canonical
                .append('|').append(evidence.evidenceId())
                .append('|').append(evidence.riskType())
                .append('|').append(evidence.title())
                .append('|').append(evidence.sourceUrl())
                .append('|').append(evidence.contentSnapshotId())
                .append('|').append(evidence.contentHash())
                .append('|').append(evidence.contentTruncated()));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<ReportEvidenceItem> confirmedEvidence(UUID taskId) {
        Map<UUID, EvidenceContentSnapshot> latestCaptured = publicIntelligence
            .findContentSnapshotsByTaskId(taskId)
            .stream()
            .filter(snapshot -> snapshot.status() == EvidenceContentStatus.CAPTURED)
            .collect(Collectors.toMap(
                EvidenceContentSnapshot::evidenceId,
                Function.identity(),
                (left, right) -> left.capturedAt().isAfter(right.capturedAt())
                    ? left
                    : right
            ));

        return publicIntelligence.findEvidenceByTaskId(taskId)
            .stream()
            .filter(evidence ->
                evidence.verificationStatus() == EvidenceVerificationStatus.CONFIRMED
            )
            .sorted(Comparator.comparing(PublicEvidence::evidenceId))
            .map(evidence -> {
                EvidenceContentSnapshot content = latestCaptured.get(evidence.evidenceId());
                String excerpt = content != null
                    && content.extractedText() != null
                    && !content.extractedText().isBlank()
                    ? content.extractedText()
                    : evidence.snippet();
                String sourceUrl = content != null
                    && content.finalUrl() != null
                    && !content.finalUrl().isBlank()
                    ? content.finalUrl()
                    : evidence.sourceUrl();
                return new ReportEvidenceItem(
                    evidence.evidenceId(),
                    evidence.riskType(),
                    evidence.title(),
                    excerpt,
                    evidence.sourceProvider(),
                    sourceUrl,
                    evidence.sourceDomain(),
                    evidence.publishedAt(),
                    content == null ? evidence.capturedAt() : content.capturedAt(),
                    content == null ? null : content.contentSnapshotId(),
                    content == null
                        ? evidence.contentHash()
                        : content.extractedTextHash(),
                    content != null && content.truncated()
                );
            })
            .toList();
    }

    private static ReportDiff buildInitialDiff(
        DataSnapshot snapshot,
        RiskScoreSnapshot score,
        LocalDate reportDate
    ) {
        return new ReportDiff(
            List.of(),
            null,
            reportDate.toString(),
            null,
            decimal(score.originalScore()),
            decimal(score.manualScore()),
            snapshot.riskEvents().size(),
            "基于本次冻结的企业数据、已确认公开证据和评分结果直接生成报告。",
            null, 0, null, null, null, null, null
        );
    }

    private static void addChange(List<ReportFieldChange> changes, String field, String before, String after) {
        changes.add(new ReportFieldChange(field, before, after));
    }

    private static String fact(DataSnapshot snapshot, String field) {
        if (snapshot == null) return null;
        return switch (field) {
            case "name" -> snapshot.companyFacts().canonicalName();
            case "creditCode" -> snapshot.companyFacts().unifiedCreditCode();
            case "legalRepresentative" -> snapshot.companyFacts().legalRepresentative();
            case "registrationStatus" -> snapshot.companyFacts().registrationStatus();
            case "registeredAddress" -> snapshot.companyFacts().registeredAddress();
            default -> null;
        };
    }

    private static void appendRuleHitChanges(
        List<ReportFieldChange> changes,
        RiskScoreSnapshot previous,
        RiskScoreSnapshot current
    ) {
        Map<String, RiskRuleHit> before = previous == null
            ? Map.of()
            : previous.ruleHits().stream().collect(Collectors.toMap(
                RiskRuleHit::ruleCode,
                Function.identity(),
                (left, right) -> left
            ));
        Map<String, RiskRuleHit> after = current.ruleHits().stream().collect(
            Collectors.toMap(
                RiskRuleHit::ruleCode,
                Function.identity(),
                (left, right) -> left
            )
        );
        java.util.TreeSet<String> codes = new java.util.TreeSet<>();
        codes.addAll(before.keySet());
        codes.addAll(after.keySet());
        for (String code : codes) {
            RiskRuleHit oldHit = before.get(code);
            RiskRuleHit newHit = after.get(code);
            addChange(
                changes,
                "评分依据 / " + (newHit == null ? oldHit.ruleName() : newHit.ruleName()),
                ruleHitValue(oldHit),
                ruleHitValue(newHit)
            );
        }
    }

    private static String ruleHitValue(RiskRuleHit hit) {
        if (hit == null) return null;
        return "%s 分 · %s · %d 个引用".formatted(
            decimal(hit.score()),
            hit.riskType() == null ? "STRUCTURED_RULE" : hit.riskType().name(),
            hit.references().size()
        );
    }

    private void emit(UUID taskId, String eventType, Map<String, ?> payload) {
        Map<String, String> eventPayload = payload.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> String.valueOf(entry.getValue())
            ));
        TaskEventRecord event = events.append(
            taskId,
            eventType,
            eventPayload,
            clock.instant()
        );
        eventPublisher.publish(event);
    }

    private static void validateGenerated(byte[] content) {
        if (content == null || content.length < 1024
            || content[0] != 'P' || content[1] != 'K') {
            throw new ReportValidationException("Renderer did not return a valid DOCX package");
        }
    }

    private static String requireOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new ReportValidationException("operatorId is required");
        }
        return operatorId.trim();
    }

    private static String safeFailure(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message.substring(0, Math.min(message.length(), 1000));
    }

    private static String safeFilename(String value) {
        String candidate = value == null ? "企业" : value;
        return candidate.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
