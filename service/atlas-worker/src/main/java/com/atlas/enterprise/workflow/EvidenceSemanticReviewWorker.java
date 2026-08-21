package com.atlas.enterprise.workflow;

import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewJob;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewRun;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticSuggestion;
import com.atlas.enterprise.intelligence.application.PublicIntelligenceApplicationService;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticReviewJobRepository;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticReviewJobRunner;
import com.atlas.enterprise.risk.RiskLevel;
import com.atlas.enterprise.risk.RiskScoringPolicy;
import com.atlas.enterprise.risk.application.RiskRulePolicyResolver;
import com.atlas.enterprise.task.application.AutonomousTaskCompletionService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvidenceSemanticReviewWorker implements EvidenceSemanticReviewJobRunner {
    private final PublicIntelligenceApplicationService publicIntelligence;
    private final EvidenceSemanticReviewJobRepository jobs;
    private final RiskRulePolicyResolver policyResolver;
    private final AutonomousTaskCompletionService autonomousCompletion;
    private final Executor executor;
    private final Clock clock;
    private final Set<UUID> scheduledJobs = ConcurrentHashMap.newKeySet();

    public EvidenceSemanticReviewWorker(
        PublicIntelligenceApplicationService publicIntelligence,
        EvidenceSemanticReviewJobRepository jobs,
        RiskRulePolicyResolver policyResolver,
        AutonomousTaskCompletionService autonomousCompletion,
        @Qualifier("semanticReviewExecutor") Executor executor,
        Clock clock
    ) {
        this.publicIntelligence = publicIntelligence;
        this.jobs = jobs;
        this.policyResolver = policyResolver;
        this.autonomousCompletion = autonomousCompletion;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public synchronized EvidenceSemanticReviewJob start(UUID taskId) {
        Optional<EvidenceSemanticReviewJob> active = jobs.findActiveByTaskId(taskId);
        if (active.isPresent()) {
            if (active.get().status() == EvidenceSemanticReviewJob.Status.QUEUED) {
                submitAfterCommit(active.get().reviewJobId());
            }
            return active.get();
        }
        Instant now = clock.instant();
        EvidenceSemanticReviewJob job = jobs.save(new EvidenceSemanticReviewJob(
            UUID.randomUUID(), taskId, EvidenceSemanticReviewJob.Status.QUEUED,
            0, 0, 0, 0, null, null,
            0, 0, 0, 0,
            null, null, List.of(), null, null, false,
            now, null, null, now
        ));
        submitAfterCommit(job.reviewJobId());
        return job;
    }

    private void submitAfterCommit(UUID reviewJobId) {
        if (!scheduledJobs.add(reviewJobId)) return;
        Runnable submit = () -> executor.execute(() -> {
            try {
                execute(reviewJobId);
            } finally {
                scheduledJobs.remove(reviewJobId);
            }
        });
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        scheduledJobs.remove(reviewJobId);
                    }
                }
            }
        );
    }

    @Override
    public Optional<EvidenceSemanticReviewJob> latest(UUID taskId) {
        return jobs.findLatestByTaskId(taskId);
    }

    @Override
    public EvidenceSemanticReviewJob cancel(UUID taskId, UUID reviewJobId) {
        EvidenceSemanticReviewJob job = jobs.findById(reviewJobId)
            .filter(value -> value.taskId().equals(taskId))
            .orElseThrow(() -> new IllegalArgumentException(
                "Semantic review job not found: " + reviewJobId
            ));
        if (!job.active()) {
            return job;
        }
        Instant now = clock.instant();
        return jobs.update(copy(
            job,
            EvidenceSemanticReviewJob.Status.CANCEL_REQUESTED,
            job.totalCount(),
            job.processedCount(),
            job.reviewedCount(),
            job.failedCount(),
            job.provider(),
            job.model(),
            "将在当前模型批次结束后取消",
            true,
            job.startedAt(),
            null,
            now
        ));
    }

    @EventListener(ContextRefreshedEvent.class)
    public void recoverInterruptedJobs() {
        jobs.failInterruptedJobs(
            clock.instant(),
            "服务重启中断了模型研判，请重新执行补跑"
        );
    }

    private void execute(UUID reviewJobId) {
        EvidenceSemanticReviewJob queued = jobs.findById(reviewJobId).orElse(null);
        if (queued == null) return;
        if (queued.cancelRequested()) {
            finishCancelled(queued);
            return;
        }
        Instant startedAt = clock.instant();
        EvidenceSemanticReviewJob running = jobs.update(copy(
            queued,
            EvidenceSemanticReviewJob.Status.RUNNING,
            queued.totalCount(), 0, 0, 0,
            queued.provider(), queued.model(), null, false,
            startedAt, null, startedAt
        ));
        try {
            EvidenceSemanticReviewRun result = publicIntelligence.semanticReview(
                running.taskId(),
                (total, processed, reviewed, failed, provider, model) ->
                    updateProgress(
                        reviewJobId, total, processed, reviewed, failed, provider, model
                    ),
                () -> jobs.findById(reviewJobId)
                    .map(EvidenceSemanticReviewJob::cancelRequested)
                    .orElse(true)
            );
            EvidenceSemanticReviewJob current = jobs.findById(reviewJobId).orElse(running);
            if (current.cancelRequested()) {
                finishCancelled(current);
                return;
            }
            Instant finishedAt = clock.instant();
            EvidenceSemanticReviewJob.Status status = result.failedCount() > 0
                ? EvidenceSemanticReviewJob.Status.PARTIAL_FAILED
                : EvidenceSemanticReviewJob.Status.SUCCEEDED;
            EvidenceSemanticReviewJob completed = copy(
                current, status,
                current.totalCount(), current.processedCount(),
                result.reviewedCount(), result.failedCount(),
                result.provider(), result.model(),
                result.failedCount() > 0 ? "部分证据未完成，可再次补跑" : null,
                false, current.startedAt(), finishedAt, finishedAt
            );
            jobs.update(withAdvisory(
                withUsage(completed, result.usage()),
                scoreAdvisory(result.suggestions(), policyResolver.resolve(result.taskId()))
            ));
            autonomousCompletion.completeIfReady(result.taskId());
        } catch (RuntimeException exception) {
            EvidenceSemanticReviewJob current = jobs.findById(reviewJobId).orElse(running);
            if (current.cancelRequested()) {
                finishCancelled(current);
                return;
            }
            Instant finishedAt = clock.instant();
            jobs.update(copy(
                current, EvidenceSemanticReviewJob.Status.FAILED,
                current.totalCount(), current.processedCount(),
                current.reviewedCount(), current.failedCount(),
                current.provider(), current.model(), safeMessage(exception),
                false, current.startedAt(), finishedAt, finishedAt
            ));
        }
    }

    private void updateProgress(
        UUID reviewJobId,
        int total,
        int processed,
        int reviewed,
        int failed,
        String provider,
        String model
    ) {
        EvidenceSemanticReviewJob current = jobs.findById(reviewJobId).orElse(null);
        if (current == null || !current.active()) return;
        EvidenceSemanticReviewJob.Status status = current.cancelRequested()
            ? EvidenceSemanticReviewJob.Status.CANCEL_REQUESTED
            : EvidenceSemanticReviewJob.Status.RUNNING;
        jobs.update(copy(
            current, status, total, processed, reviewed, failed,
            provider, model, current.errorMessage(), current.cancelRequested(),
            current.startedAt(), null, clock.instant()
        ));
    }

    private void finishCancelled(EvidenceSemanticReviewJob current) {
        Instant finishedAt = clock.instant();
        jobs.update(copy(
            current, EvidenceSemanticReviewJob.Status.CANCELLED,
            current.totalCount(), current.processedCount(),
            current.reviewedCount(), current.failedCount(),
            current.provider(), current.model(),
            "模型辅助研判已取消，已保存的建议不会丢失",
            true, current.startedAt(), finishedAt, finishedAt
        ));
    }

    private static EvidenceSemanticReviewJob copy(
        EvidenceSemanticReviewJob source,
        EvidenceSemanticReviewJob.Status status,
        int total,
        int processed,
        int reviewed,
        int failed,
        String provider,
        String model,
        String error,
        boolean cancelRequested,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt
    ) {
        return new EvidenceSemanticReviewJob(
            source.reviewJobId(), source.taskId(), status,
            total, processed, reviewed, failed, provider, model,
            source.modelCallCount(), source.promptTokenCount(),
            source.completionTokenCount(), source.totalTokenCount(),
            source.modelSuggestedScore(), source.modelSuggestedRiskLevel(),
            source.modelScoreEvidenceIds(), source.advisoryRuleVersion(),
            error,
            cancelRequested, source.createdAt(), startedAt, finishedAt, updatedAt
        );
    }

    private static ModelScoreAdvisory scoreAdvisory(
        List<EvidenceSemanticSuggestion> suggestions,
        RiskScoringPolicy policy
    ) {
        List<EvidenceSemanticSuggestion> eligible = suggestions.stream()
            .filter(item -> item.relevance() == EvidenceSemanticSuggestion.Relevance.RELEVANT)
            .filter(item -> item.confidence() >= 0.8D)
            .filter(item -> policy.floorFor(item.riskType()).signum() > 0)
            .toList();
        BigDecimal score = eligible.stream()
            .map(item -> policy.floorFor(item.riskType()))
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        List<UUID> evidenceIds = eligible.stream()
            .map(EvidenceSemanticSuggestion::evidenceId)
            .distinct()
            .toList();
        return new ModelScoreAdvisory(
            score,
            RiskLevel.from(score),
            evidenceIds,
            policy.version()
        );
    }

    private static EvidenceSemanticReviewJob withAdvisory(
        EvidenceSemanticReviewJob source,
        ModelScoreAdvisory advisory
    ) {
        return new EvidenceSemanticReviewJob(
            source.reviewJobId(), source.taskId(), source.status(),
            source.totalCount(), source.processedCount(), source.reviewedCount(),
            source.failedCount(), source.provider(), source.model(),
            source.modelCallCount(), source.promptTokenCount(),
            source.completionTokenCount(), source.totalTokenCount(),
            advisory.score(), advisory.riskLevel(), advisory.evidenceIds(),
            advisory.ruleVersion(), source.errorMessage(), source.cancelRequested(),
            source.createdAt(), source.startedAt(), source.finishedAt(), source.updatedAt()
        );
    }

    private static EvidenceSemanticReviewJob withUsage(
        EvidenceSemanticReviewJob source,
        com.atlas.enterprise.intelligence.application.ModelUsage usage
    ) {
        return new EvidenceSemanticReviewJob(
            source.reviewJobId(), source.taskId(), source.status(),
            source.totalCount(), source.processedCount(), source.reviewedCount(),
            source.failedCount(), source.provider(), source.model(),
            usage.callCount(), usage.promptTokens(), usage.completionTokens(),
            usage.totalTokens(), source.modelSuggestedScore(),
            source.modelSuggestedRiskLevel(), source.modelScoreEvidenceIds(),
            source.advisoryRuleVersion(), source.errorMessage(),
            source.cancelRequested(), source.createdAt(), source.startedAt(),
            source.finishedAt(), source.updatedAt()
        );
    }

    private record ModelScoreAdvisory(
        BigDecimal score,
        RiskLevel riskLevel,
        List<UUID> evidenceIds,
        String ruleVersion
    ) {
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
