package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.company.application.SnapshotNotFoundException;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.application.TaskConnectorConfigurationResolver;
import com.atlas.enterprise.configuration.application.SkillExecutionGate;
import com.atlas.enterprise.intelligence.EntityMatchStatus;
import com.atlas.enterprise.intelligence.EvidenceContentSnapshot;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.EvidenceDecision;
import com.atlas.enterprise.intelligence.EvidenceGrade;
import com.atlas.enterprise.intelligence.EvidenceVerificationStatus;
import com.atlas.enterprise.intelligence.ProviderCapabilities;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.PublicIntelligenceRun;
import com.atlas.enterprise.intelligence.SearchBatch;
import com.atlas.enterprise.intelligence.SearchBatchStatus;
import com.atlas.enterprise.intelligence.SearchExecution;
import com.atlas.enterprise.intelligence.SearchRequest;
import com.atlas.enterprise.intelligence.SearchResult;
import com.atlas.enterprise.intelligence.port.PublicIntelligenceRepository;
import com.atlas.enterprise.intelligence.port.EvidenceContentFetcher;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticModel;
import com.atlas.enterprise.intelligence.port.PublicSearchProvider;
import com.atlas.enterprise.intelligence.port.PublicSearchProviderRegistry;
import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.risk.RiskType;
import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicIntelligenceApplicationService {
    private static final int MODEL_REVIEW_BATCH_SIZE = 3;

    private final PublicSearchProviderRegistry providerRegistry;
    private final EvidenceContentFetcher contentFetcher;
    private final PublicIntelligenceRepository repository;
    private final DataSnapshotRepository snapshots;
    private final TaskRepository tasks;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;
    private final PublicSearchQueryPlanner queryPlanner;
    private final EvidenceNormalizer normalizer;
    private final CompanyAliasApplicationService companyAliases;
    private final TaskConnectorConfigurationResolver connectorConfigurations;
    private final SkillExecutionGate skillGate;
    private final List<EvidenceSemanticModel> semanticModels;

    public PublicIntelligenceApplicationService(
        PublicSearchProviderRegistry providerRegistry,
        EvidenceContentFetcher contentFetcher,
        PublicIntelligenceRepository repository,
        DataSnapshotRepository snapshots,
        TaskRepository tasks,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        CompanyAliasApplicationService companyAliases,
        TaskConnectorConfigurationResolver connectorConfigurations,
        SkillExecutionGate skillGate,
        List<EvidenceSemanticModel> semanticModels,
        Clock clock
    ) {
        this.providerRegistry = providerRegistry;
        this.contentFetcher = contentFetcher;
        this.repository = repository;
        this.snapshots = snapshots;
        this.tasks = tasks;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.companyAliases = companyAliases;
        this.connectorConfigurations = connectorConfigurations;
        this.skillGate = skillGate;
        this.semanticModels = List.copyOf(semanticModels);
        this.clock = clock;
        this.queryPlanner = new PublicSearchQueryPlanner();
        this.normalizer = new EvidenceNormalizer();
    }

    @Transactional(noRollbackFor = RequiredSearchProviderFailedException.class)
    public PublicIntelligenceRun search(UUID taskId) {
        requireTask(taskId);
        skillGate.requireEnabled(taskId, "intelligence.search");
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        List<CompanyAlias> aliases = companyAliases.confirmed(snapshot);
        List<PublicSearchQueryPlanner.PlannedQuery> plannedQueries = queryPlanner.plan(
            snapshot.companyFacts(),
            aliases,
            configuredQueryTemplates(taskId),
            configuredSourceScopes(taskId)
        );
        List<SearchExecution> existingSearches = repository.findSearchesByTaskId(taskId);
        List<PublicSearchProvider> providers = providerRegistry.providers(taskId);
        if (providers.isEmpty()) {
            throw new RequiredSearchProviderFailedException(
                "none",
                "SEARCH_PROVIDER_NOT_CONFIGURED",
                "No public search provider is configured"
            );
        }
        if (allQueriesCompleted(existingSearches, providers, plannedQueries)) {
            return new PublicIntelligenceRun(
                taskId,
                existingSearches,
                repository.findEvidenceByTaskId(taskId)
            );
        }

        Set<String> dedupeKeys = new HashSet<>();
        for (PublicEvidence existing : repository.findEvidenceByTaskId(taskId)) {
            dedupeKeys.add(normalizer.dedupeKey(
                existing.normalizedUrl(),
                existing.contentHash()
            ));
        }

        List<SearchExecution> searches = new ArrayList<>();
        List<PublicEvidence> evidence = new ArrayList<>();
        RequiredSearchProviderFailedException requiredFailure = null;
        Instant requestedAt = clock.instant();

        for (PublicSearchProvider provider : providers) {
            ProviderCapabilities capabilities = provider.capabilities();
            for (PublicSearchQueryPlanner.PlannedQuery planned : plannedQueries) {
                if (queryCompleted(existingSearches, capabilities.provider(), planned)) {
                    continue;
                }
                SearchRequest request = new SearchRequest(
                    taskId,
                    snapshot.atlasCompanyId(),
                    snapshot.companyFacts().canonicalName(),
                    snapshot.companyFacts().unifiedCreditCode(),
                    planned.query(),
                    planned.targetRisk(),
                    planned.sourceScope(),
                    planned.includeDomains(),
                    planned.includeRawContent(),
                    planned.topic(),
                    requestedAt
                );
                SearchBatch batch = safeSearch(provider, request);
                SearchExecution execution = repository.saveSearch(new SearchExecution(
                    UUID.randomUUID(),
                    taskId,
                    snapshot.snapshotId(),
                    capabilities.provider(),
                    capabilities.mode(),
                    request.query(),
                    request.targetRisk(),
                    request.sourceScope(),
                    batch.status(),
                    batch.results().size(),
                    batch.failureCode(),
                    batch.failureMessage(),
                    batch.searchedAt()
                ));
                searches.add(execution);

                if (batch.status() == SearchBatchStatus.FAILED) {
                    if (capabilities.required() && requiredFailure == null) {
                        requiredFailure = new RequiredSearchProviderFailedException(
                            capabilities.provider(),
                            batch.failureCode(),
                            batch.failureMessage()
                        );
                    }
                    continue;
                }
                for (SearchResult result : batch.results()) {
                    PublicEvidence candidate = toEvidence(
                        snapshot,
                        execution,
                        capabilities,
                        aliases,
                        result
                    );
                    if (candidate == null) {
                        continue;
                    }
                    if (candidate.entityMatchStatus()
                        != EntityMatchStatus.MATCHED) {
                        continue;
                    }
                    String dedupeKey = normalizer.dedupeKey(
                        candidate.normalizedUrl(),
                        candidate.contentHash()
                    );
                    if (dedupeKeys.add(dedupeKey)) {
                        PublicEvidence saved = repository.saveEvidence(candidate);
                        saveProviderContentSnapshot(saved, result);
                        evidence.add(saved);
                    }
                }
            }
        }
        if (requiredFailure != null) {
            throw requiredFailure;
        }
        return new PublicIntelligenceRun(
            taskId,
            repository.findSearchesByTaskId(taskId),
            repository.findEvidenceByTaskId(taskId)
        );
    }

    @Transactional(readOnly = true)
    public List<PublicEvidence> evidence(UUID taskId) {
        requireTask(taskId);
        return repository.findEvidenceByTaskId(taskId);
    }

    @Transactional(readOnly = true)
    public boolean semanticReviewAvailable(UUID taskId) {
        requireTask(taskId);
        return semanticModels.stream().anyMatch(model -> model.available(taskId));
    }

    public EvidenceSemanticReviewRun semanticReview(UUID taskId) {
        return semanticReview(
            taskId,
            EvidenceSemanticReviewProgress.noop(),
            () -> false
        );
    }

    public EvidenceSemanticReviewRun semanticReview(
        UUID taskId,
        EvidenceSemanticReviewProgress progress,
        java.util.function.BooleanSupplier cancellationRequested
    ) {
        requireTask(taskId);
        skillGate.requireEnabled(taskId, "intelligence.search");
        DataSnapshot snapshot = snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
        EvidenceSemanticModel semanticModel = semanticModels.stream()
            .filter(model -> model.available(taskId))
            .findFirst()
            .orElseThrow(() -> new PublicIntelligenceValidationException(
                "No published semantic model is available for this task"
            ));
        List<PublicEvidence> pending = repository.findEvidenceByTaskId(taskId).stream()
            .filter(item -> item.verificationStatus() == EvidenceVerificationStatus.UNVERIFIED)
            .filter(item -> !item.metadata().containsKey("llm_reviewed_at"))
            .toList();
        if (pending.isEmpty()) {
            throw new PublicIntelligenceValidationException(
                "No pending evidence is available for model review"
            );
        }
        String provider = semanticModel.provider(taskId);
        String model = semanticModel.model(taskId);
        progress.update(pending.size(), 0, 0, 0, provider, model);
        List<String> aliases = companyAliases.confirmed(snapshot).stream()
            .map(CompanyAlias::aliasName)
            .distinct()
            .toList();
        List<EvidenceSemanticSuggestion> suggestions = new ArrayList<>();
        ModelUsage modelUsage = ModelUsage.NONE;
        int failedCount = 0;
        int processedCount = 0;
        RuntimeException lastFailure = null;
        Map<UUID, PublicEvidence> allowed = pending.stream().collect(
            java.util.stream.Collectors.toMap(PublicEvidence::evidenceId, item -> item)
        );
        for (int start = 0; start < pending.size(); start += MODEL_REVIEW_BATCH_SIZE) {
            if (cancellationRequested.getAsBoolean()) {
                break;
            }
            int end = Math.min(pending.size(), start + MODEL_REVIEW_BATCH_SIZE);
            List<PublicEvidence> batch = pending.subList(start, end);
            try {
                EvidenceSemanticReviewOutcome outcome = semanticModel.review(
                    new EvidenceSemanticReviewRequest(
                        taskId,
                        snapshot.companyFacts().canonicalName(),
                        aliases,
                        batch
                    )
                );
                List<EvidenceSemanticSuggestion> batchSuggestions = outcome.suggestions();
                modelUsage = modelUsage.plus(outcome.usage());
                Set<UUID> batchEvidenceIds = batch.stream()
                    .map(PublicEvidence::evidenceId)
                    .collect(java.util.stream.Collectors.toSet());
                Set<UUID> returnedEvidenceIds = new HashSet<>();
                for (EvidenceSemanticSuggestion suggestion : batchSuggestions) {
                    if (!batchEvidenceIds.contains(suggestion.evidenceId())
                        || !returnedEvidenceIds.add(suggestion.evidenceId())) {
                        throw new PublicIntelligenceValidationException(
                            "Model returned duplicate or out-of-batch evidence"
                        );
                    }
                }
                persistSemanticSuggestions(
                    taskId,
                    batchSuggestions,
                    allowed,
                    provider,
                    model,
                    clock.instant()
                );
                suggestions.addAll(batchSuggestions);
                failedCount += batch.size() - batchSuggestions.size();
            } catch (RuntimeException exception) {
                failedCount += batch.size();
                lastFailure = exception;
            }
            processedCount += batch.size();
            progress.update(
                pending.size(),
                processedCount,
                suggestions.size(),
                failedCount,
                provider,
                model
            );
        }
        if (suggestions.isEmpty() && lastFailure != null) {
            throw lastFailure;
        }
        boolean cancelled = processedCount < pending.size()
            || cancellationRequested.getAsBoolean();
        AutomatedEvidenceDecisionSummary automaticDecisions =
            !cancelled && semanticModel.automaticDecisionEnabled(taskId)
                ? applyAutomatedDecisions(
                    taskId,
                    suggestions,
                    semanticModel.automaticDecisionThreshold(taskId),
                    provider,
                    model
                )
                : pendingDecisionSummary(taskId, 0, 0);
        Instant reviewedAt = clock.instant();
        TaskEventRecord event = events.append(
            taskId,
            "public.intelligence.model.reviewed",
            Map.ofEntries(
                Map.entry("provider", provider),
                Map.entry("model", model),
                Map.entry("requestedCount", Integer.toString(pending.size())),
                Map.entry("processedCount", Integer.toString(processedCount)),
                Map.entry("reviewedCount", Integer.toString(suggestions.size())),
                Map.entry("failedCount", Integer.toString(failedCount)),
                Map.entry("modelCallCount", Integer.toString(modelUsage.callCount())),
                Map.entry("promptTokens", Integer.toString(modelUsage.promptTokens())),
                Map.entry("completionTokens", Integer.toString(modelUsage.completionTokens())),
                Map.entry("totalTokens", Integer.toString(modelUsage.totalTokens())),
                Map.entry(
                    "cancelled",
                    Boolean.toString(cancelled)
                ),
                Map.entry(
                    "automaticConfirmedCount",
                    Integer.toString(automaticDecisions.confirmedCount())
                ),
                Map.entry(
                    "automaticRejectedCount",
                    Integer.toString(automaticDecisions.rejectedCount())
                ),
                Map.entry(
                    "manualReviewCount",
                    Integer.toString(automaticDecisions.manualReviewCount())
                ),
                Map.entry(
                    "operatorDecisionRequired",
                    Boolean.toString(automaticDecisions.operatorDecisionRequired())
                )
            ),
            reviewedAt
        );
        eventPublisher.publish(event);
        return new EvidenceSemanticReviewRun(
            taskId,
            provider,
            model,
            suggestions.size(),
            failedCount,
            suggestions,
            modelUsage,
            reviewedAt,
            automaticDecisions.operatorDecisionRequired()
        );
    }

    private AutomatedEvidenceDecisionSummary applyAutomatedDecisions(
        UUID taskId,
        List<EvidenceSemanticSuggestion> suggestions,
        double threshold,
        String provider,
        String model
    ) {
        int confirmed = 0;
        int rejected = 0;
        for (EvidenceSemanticSuggestion suggestion : suggestions) {
            PublicEvidence current = repository.findEvidenceById(suggestion.evidenceId())
                .filter(item -> item.taskId().equals(taskId))
                .orElse(null);
            if (current == null
                || current.verificationStatus() != EvidenceVerificationStatus.UNVERIFIED
                || suggestion.confidence() < threshold
                || suggestion.relevance() == EvidenceSemanticSuggestion.Relevance.UNCERTAIN) {
                continue;
            }

            String decisionReason = automaticDecisionReason(
                provider,
                model,
                suggestion
            );
            if (suggestion.relevance()
                == EvidenceSemanticSuggestion.Relevance.IRRELEVANT) {
                decide(
                    taskId,
                    current.evidenceId(),
                    EvidenceVerificationStatus.REJECTED,
                    decisionReason,
                    "atlas-agent:model"
                );
                rejected++;
                continue;
            }

            boolean confirmable = suggestion.riskType() != RiskType.OTHER
                && current.entityMatchStatus() == EntityMatchStatus.MATCHED
                && current.grade() != EvidenceGrade.LEAD
                && !suggestion.reason().isBlank()
                && !suggestion.summary().isBlank();
            if (!confirmable) {
                continue;
            }
            repository.updateEvidence(current.withRiskType(suggestion.riskType()));
            try {
                decide(
                    taskId,
                    current.evidenceId(),
                    EvidenceVerificationStatus.CONFIRMED,
                    decisionReason,
                    "atlas-agent:model"
                );
                confirmed++;
            } catch (PublicIntelligenceValidationException exception) {
                // A cited result without captured source content must remain in
                // the operator queue instead of being confirmed from a snippet.
            }
        }
        return pendingDecisionSummary(taskId, confirmed, rejected);
    }

    private AutomatedEvidenceDecisionSummary pendingDecisionSummary(
        UUID taskId,
        int confirmed,
        int rejected
    ) {
        int manualReview = (int) repository.findEvidenceByTaskId(taskId).stream()
            .filter(item -> item.verificationStatus()
                == EvidenceVerificationStatus.UNVERIFIED)
            .count();
        return new AutomatedEvidenceDecisionSummary(
            confirmed,
            rejected,
            manualReview
        );
    }

    private static String automaticDecisionReason(
        String provider,
        String model,
        EvidenceSemanticSuggestion suggestion
    ) {
        String reason = suggestion.reason().isBlank()
            ? "模型未提供补充理由"
            : suggestion.reason();
        return "Atlas自动研判：%s/%s，置信度 %.2f；%s".formatted(
            provider,
            model,
            suggestion.confidence(),
            reason
        );
    }

    private void persistSemanticSuggestions(
        UUID taskId,
        List<EvidenceSemanticSuggestion> suggestions,
        Map<UUID, PublicEvidence> allowed,
        String provider,
        String model,
        Instant reviewedAt
    ) {
        for (EvidenceSemanticSuggestion suggestion : suggestions) {
            PublicEvidence evidence = allowed.get(suggestion.evidenceId());
            if (evidence == null || !evidence.taskId().equals(taskId)) {
                throw new PublicIntelligenceValidationException(
                    "Model returned evidence outside the pending review set"
                );
            }
            Map<String, String> metadata = new LinkedHashMap<>(evidence.metadata());
            metadata.put("llm_relevance", suggestion.relevance().name());
            metadata.put("llm_risk_type", suggestion.riskType().name());
            metadata.put("llm_confidence", Double.toString(suggestion.confidence()));
            metadata.put("llm_reason", suggestion.reason());
            metadata.put("llm_summary", suggestion.summary());
            metadata.put("llm_provider", provider);
            metadata.put("llm_model", model);
            metadata.put("llm_reviewed_at", reviewedAt.toString());
            repository.updateEvidence(evidence.withMetadata(metadata));
        }
    }

    @Transactional(readOnly = true)
    public List<SearchExecution> searches(UUID taskId) {
        requireTask(taskId);
        return repository.findSearchesByTaskId(taskId);
    }

    @Transactional(noRollbackFor = PublicIntelligenceValidationException.class)
    public EvidenceDecision decide(
        UUID taskId,
        UUID evidenceId,
        EvidenceVerificationStatus decision,
        String reason,
        String operatorId
    ) {
        requireTask(taskId);
        validateDecision(decision, reason, operatorId);
        PublicEvidence before = repository.findEvidenceById(evidenceId)
            .orElseThrow(() -> new EvidenceNotFoundException(evidenceId));
        if (!before.taskId().equals(taskId)) {
            throw new PublicIntelligenceValidationException(
                "Evidence does not belong to task " + taskId
            );
        }
        if (decision == EvidenceVerificationStatus.CONFIRMED
            && before.grade() == EvidenceGrade.LEAD) {
            throw new PublicIntelligenceValidationException(
                "Evidence without an accessible citation cannot be confirmed"
            );
        }
        if (decision == EvidenceVerificationStatus.CONFIRMED) {
            EvidenceContentSnapshot content = captureOrFindContent(before);
            if (content.status() != EvidenceContentStatus.CAPTURED) {
                throw new PublicIntelligenceValidationException(
                    "Evidence source content could not be captured; keep it for manual review"
                );
            }
        }
        Instant now = clock.instant();
        repository.updateEvidence(before.withVerificationStatus(decision));
        EvidenceDecision saved = repository.saveDecision(new EvidenceDecision(
            UUID.randomUUID(),
            taskId,
            evidenceId,
            decision,
            reason.trim(),
            operatorId.trim(),
            now
        ));
        TaskEventRecord event = events.append(
            taskId,
            "public.intelligence.evidence.decided",
            Map.of(
                "evidenceId", evidenceId.toString(),
                "decisionId", saved.decisionId().toString(),
                "decision", saved.decision().name(),
                "operatorId", saved.operatorId()
            ),
            now
        );
        eventPublisher.publish(event);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EvidenceDecision> decisions(UUID taskId) {
        requireTask(taskId);
        return repository.findDecisionsByTaskId(taskId);
    }

    @Transactional
    public EvidenceContentSnapshot captureContent(
        UUID taskId,
        UUID evidenceId
    ) {
        requireTask(taskId);
        PublicEvidence evidence = requireTaskEvidence(taskId, evidenceId);
        if (evidence.normalizedUrl() == null || evidence.normalizedUrl().isBlank()) {
            throw new PublicIntelligenceValidationException(
                "Evidence does not have an accessible source URL"
            );
        }
        return captureOrFindContent(evidence);
    }

    @Transactional(readOnly = true)
    public List<EvidenceContentSnapshot> contentSnapshots(UUID taskId) {
        requireTask(taskId);
        return repository.findContentSnapshotsByTaskId(taskId);
    }

    @Transactional(readOnly = true)
    public EvidenceContentSnapshot latestContentSnapshot(
        UUID taskId,
        UUID evidenceId
    ) {
        requireTaskEvidence(taskId, evidenceId);
        return repository.findLatestContentSnapshot(evidenceId)
            .orElseThrow(() -> new PublicIntelligenceValidationException(
                "Evidence content has not been captured"
            ));
    }

    @Transactional(readOnly = true)
    public List<ConfirmedRiskEvent> confirmedRiskEvents(UUID taskId) {
        requireTask(taskId);
        return repository.findEvidenceByTaskId(taskId).stream()
            .filter(item -> item.verificationStatus() == EvidenceVerificationStatus.CONFIRMED)
            .filter(item -> item.riskType() != RiskType.OTHER)
            .map(item -> new ConfirmedRiskEvent(
                item.riskType(),
                item.evidenceId().toString(),
                item.title(),
                List.of(item.evidenceId().toString())
            ))
            .toList();
    }

    private List<String> configuredQueryTemplates(UUID taskId) {
        return connectorConfigurations.resolve(taskId, ConfigurationCategory.SEARCH).stream()
            .filter(configuration -> configuration.definition().enabled())
            .filter(configuration -> !"IDENTITY_SOURCE_AGGREGATION".equals(
                configuration.definition().settings().path("strategy").asText()
            ))
            .flatMap(configuration -> {
                var templates = configuration.definition().settings().path("query_templates");
                if (!templates.isArray()) return java.util.stream.Stream.<String>empty();
                return java.util.stream.StreamSupport.stream(templates.spliterator(), false)
                    .map(template -> template.asText("").trim())
                    .filter(template -> !template.isEmpty());
            })
            .distinct()
            .toList();
    }

    private List<PublicSearchQueryPlanner.SearchScope> configuredSourceScopes(UUID taskId) {
        return connectorConfigurations.resolve(taskId, ConfigurationCategory.SEARCH).stream()
            .filter(configuration -> configuration.definition().enabled())
            .filter(configuration -> "IDENTITY_SOURCE_AGGREGATION".equals(
                configuration.definition().settings().path("strategy").asText()
            ))
            .flatMap(configuration -> {
                var scopes = configuration.definition().settings().path("source_scopes");
                if (!scopes.isArray()) {
                    return java.util.stream.Stream.<PublicSearchQueryPlanner.SearchScope>empty();
                }
                return java.util.stream.StreamSupport.stream(scopes.spliterator(), false)
                    .map(scope -> new PublicSearchQueryPlanner.SearchScope(
                        scope.path("code").asText(),
                        scope.path("label").asText(scope.path("code").asText()),
                        java.util.stream.StreamSupport.stream(
                            scope.path("include_domains").spliterator(), false
                        ).map(item -> item.asText().trim()).filter(item -> !item.isEmpty()).toList(),
                        scope.path("include_raw_content").asBoolean(true),
                        scope.path("topic").asText("general")
                    ));
            })
            .distinct()
            .toList();
    }

    private PublicEvidence toEvidence(
        DataSnapshot snapshot,
        SearchExecution execution,
        ProviderCapabilities capabilities,
        List<CompanyAlias> aliases,
        SearchResult result
    ) {
        String normalizedUrl = normalizer.normalizeUrl(result.url());
        String contentHash = normalizer.contentHash(result.title(), result.snippet());
        EvidenceNormalizer.EntityMatch entityMatch = normalizer.matchEntity(
            snapshot.companyFacts(),
            aliases,
            result.title(),
            result.snippet()
        );
        RiskType riskType = normalizer.classifyRisk(
            execution.targetRisk(),
            result.title(),
            result.snippet()
        );
        if (normalizer.isBackgroundProfileWithoutRisk(
            normalizedUrl,
            result.title(),
            result.snippet()
        )) {
            return null;
        }
        boolean accessible = capabilities.returnsAccessibleCitations()
            && normalizer.hasAccessibleCitation(normalizedUrl);
        Map<String, String> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("source_scope", execution.sourceScope());
        if (entityMatch.matchedTerm() != null) {
            metadata.put("matched_identity_term", entityMatch.matchedTerm());
            metadata.put("matched_identity_type", entityMatch.matchedTermType());
        }
        return new PublicEvidence(
            UUID.randomUUID(),
            snapshot.taskId(),
            snapshot.atlasCompanyId(),
            execution.searchBatchId(),
            riskType,
            execution.provider(),
            result.url(),
            normalizedUrl,
            normalizer.sourceDomain(normalizedUrl),
            result.title(),
            result.snippet(),
            execution.query(),
            result.rank(),
            result.publishedAt(),
            clock.instant(),
            contentHash,
            entityMatch.status(),
            EvidenceVerificationStatus.UNVERIFIED,
            accessible && entityMatch.status() == EntityMatchStatus.MATCHED
                ? EvidenceGrade.C
                : EvidenceGrade.LEAD,
            metadata
        );
    }

    private void saveProviderContentSnapshot(
        PublicEvidence evidence,
        SearchResult result
    ) {
        String rawContent = result.rawContent();
        String sourceUrl = evidence.normalizedUrl();
        if (rawContent == null || rawContent.isBlank()
            || sourceUrl == null || sourceUrl.isBlank()) {
            return;
        }
        byte[] bytes = rawContent.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(bytes);
        repository.saveContentSnapshot(new EvidenceContentSnapshot(
            UUID.randomUUID(),
            evidence.taskId(),
            evidence.evidenceId(),
            EvidenceContentStatus.CAPTURED,
            sourceUrl,
            sourceUrl,
            200,
            "text/plain; source=search-provider",
            rawContent,
            rawContent,
            contentHash,
            contentHash,
            bytes.length,
            Boolean.parseBoolean(result.metadata().getOrDefault(
                "raw_content_truncated",
                "false"
            )),
            null,
            null,
            clock.instant()
        ));
    }

    private EvidenceContentSnapshot captureOrFindContent(PublicEvidence evidence) {
        return repository.findLatestContentSnapshot(evidence.evidenceId())
            .filter(snapshot -> snapshot.status() == EvidenceContentStatus.CAPTURED)
            .orElseGet(() -> {
                var capture = contentFetcher.fetch(evidence.normalizedUrl());
                return repository.saveContentSnapshot(new EvidenceContentSnapshot(
                    UUID.randomUUID(),
                    evidence.taskId(),
                    evidence.evidenceId(),
                    capture.status(),
                    capture.requestedUrl(),
                    capture.finalUrl(),
                    capture.httpStatus(),
                    capture.contentType(),
                    capture.rawContent(),
                    capture.extractedText(),
                    capture.rawContentHash(),
                    capture.extractedTextHash(),
                    capture.byteLength(),
                    capture.truncated(),
                    capture.failureCode(),
                    capture.failureMessage(),
                    capture.capturedAt()
                ));
            });
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append("%02x".formatted(item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private SearchBatch safeSearch(PublicSearchProvider provider, SearchRequest request) {
        try {
            SearchBatch batch = provider.search(request);
            if (!provider.capabilities().provider().equals(batch.provider())) {
                return SearchBatch.failed(
                    provider.capabilities().provider(),
                    "PROVIDER_CONTRACT_VIOLATION",
                    "Search batch provider does not match provider capabilities",
                    clock.instant()
                );
            }
            return batch;
        } catch (RuntimeException exception) {
            return SearchBatch.failed(
                provider.capabilities().provider(),
                "PROVIDER_EXCEPTION",
                exception.getMessage(),
                clock.instant()
            );
        }
    }

    private static boolean allQueriesCompleted(
        List<SearchExecution> searches,
        List<PublicSearchProvider> providers,
        List<PublicSearchQueryPlanner.PlannedQuery> plannedQueries
    ) {
        return providers.stream().allMatch(provider -> plannedQueries.stream().allMatch(
            query -> queryCompleted(
                searches,
                provider.capabilities().provider(),
                query
            )
        ));
    }

    private static boolean queryCompleted(
        List<SearchExecution> searches,
        String provider,
        PublicSearchQueryPlanner.PlannedQuery planned
    ) {
        return searches.stream().anyMatch(search ->
            search.provider().equals(provider)
                && search.query().equals(planned.query())
                && search.targetRisk() == planned.targetRisk()
                && search.sourceScope().equals(planned.sourceScope())
                && search.status() != SearchBatchStatus.FAILED
        );
    }

    private void requireTask(UUID taskId) {
        if (tasks.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
    }

    private PublicEvidence requireTaskEvidence(UUID taskId, UUID evidenceId) {
        PublicEvidence evidence = repository.findEvidenceById(evidenceId)
            .orElseThrow(() -> new EvidenceNotFoundException(evidenceId));
        if (!evidence.taskId().equals(taskId)) {
            throw new PublicIntelligenceValidationException(
                "Evidence does not belong to task " + taskId
            );
        }
        return evidence;
    }

    private static void validateDecision(
        EvidenceVerificationStatus decision,
        String reason,
        String operatorId
    ) {
        if (decision == null || decision == EvidenceVerificationStatus.UNVERIFIED) {
            throw new PublicIntelligenceValidationException(
                "decision must be CONFIRMED or REJECTED"
            );
        }
        if (reason == null || reason.isBlank()) {
            throw new PublicIntelligenceValidationException("reason is required");
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new PublicIntelligenceValidationException("operatorId is required");
        }
    }
}
