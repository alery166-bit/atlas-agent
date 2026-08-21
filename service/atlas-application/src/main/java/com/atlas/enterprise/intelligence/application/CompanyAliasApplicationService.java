package com.atlas.enterprise.intelligence.application;

import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.company.CompanyAliasRelation;
import com.atlas.enterprise.company.CompanyAliasType;
import com.atlas.enterprise.company.CompanyAliasVerificationStatus;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.port.CompanyAliasRepository;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.company.application.SnapshotNotFoundException;
import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.port.TaskEventPublisher;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.atlas.enterprise.task.port.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyAliasApplicationService {
    private final CompanyAliasRepository aliases;
    private final DataSnapshotRepository snapshots;
    private final TaskRepository tasks;
    private final TaskEventStore events;
    private final TaskEventPublisher eventPublisher;
    private final Clock clock;
    private final CompanyIdentityTerms identityTerms = new CompanyIdentityTerms();

    public CompanyAliasApplicationService(
        CompanyAliasRepository aliases,
        DataSnapshotRepository snapshots,
        TaskRepository tasks,
        TaskEventStore events,
        TaskEventPublisher eventPublisher,
        Clock clock
    ) {
        this.aliases = aliases;
        this.snapshots = snapshots;
        this.tasks = tasks;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public List<CompanyAlias> list(UUID taskId) {
        DataSnapshot snapshot = requireSnapshot(taskId);
        synchronizeStructured(snapshot);
        return aliases.findByCompanyId(snapshot.atlasCompanyId());
    }

    @Transactional
    public List<CompanyAlias> confirmed(DataSnapshot snapshot) {
        synchronizeStructured(snapshot);
        return aliases.findConfirmedByCompanyId(snapshot.atlasCompanyId());
    }

    @Transactional
    public CompanyAlias addConfirmed(
        UUID taskId,
        String aliasName,
        CompanyAliasType aliasType,
        CompanyAliasRelation relation,
        String sourceEvidence,
        String operatorId
    ) {
        DataSnapshot snapshot = requireSnapshot(taskId);
        validate(aliasName, aliasType, relation, sourceEvidence, operatorId);
        Instant now = clock.instant();
        CompanyAlias saved = aliases.save(new CompanyAlias(
            UUID.randomUUID(),
            snapshot.atlasCompanyId(),
            aliasName,
            aliasType,
            relation,
            CompanyAliasVerificationStatus.CONFIRMED,
            "OPERATOR",
            taskId.toString(),
            sourceEvidence.trim(),
            operatorId.trim(),
            now,
            null,
            now,
            now
        ));
        TaskEventRecord event = events.append(
            taskId,
            "company.alias.confirmed",
            Map.of(
                "aliasId", saved.aliasId().toString(),
                "aliasName", saved.aliasName(),
                "aliasType", saved.aliasType().name(),
                "relation", saved.relation().name(),
                "operatorId", saved.createdBy()
            ),
            now
        );
        eventPublisher.publish(event);
        return saved;
    }

    private void synchronizeStructured(DataSnapshot snapshot) {
        Instant now = clock.instant();
        Instant validFrom = snapshot.companyFacts().dataAsOf() == null
            ? snapshot.frozenAt()
            : snapshot.companyFacts().dataAsOf();
        for (CompanyIdentityTerms.IdentityTerm term
            : identityTerms.confirmed(snapshot.companyFacts())) {
            if ("UNIFIED_CREDIT_CODE".equals(term.type())) {
                continue;
            }
            CompanyAliasType type = CompanyAliasType.valueOf(term.type());
            aliases.save(new CompanyAlias(
                UUID.randomUUID(),
                snapshot.atlasCompanyId(),
                term.value(),
                type,
                relation(type),
                CompanyAliasVerificationStatus.CONFIRMED,
                snapshot.companyFacts().sourceSystem(),
                snapshot.companyFacts().sourceRecordId(),
                "Structured company snapshot " + snapshot.snapshotId(),
                "SYSTEM",
                validFrom,
                null,
                now,
                now
            ));
        }
    }

    private DataSnapshot requireSnapshot(UUID taskId) {
        if (tasks.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        return snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
    }

    private static CompanyAliasRelation relation(CompanyAliasType type) {
        return switch (type) {
            case FORMER_NAME -> CompanyAliasRelation.FORMER_IDENTITY;
            case BRAND -> CompanyAliasRelation.OWNED_BRAND;
            case STORE -> CompanyAliasRelation.OPERATED_STORE;
            default -> CompanyAliasRelation.SAME_LEGAL_ENTITY;
        };
    }

    private static void validate(
        String aliasName,
        CompanyAliasType aliasType,
        CompanyAliasRelation relation,
        String sourceEvidence,
        String operatorId
    ) {
        if (aliasName == null || aliasName.isBlank() || aliasName.trim().length() > 256) {
            throw new PublicIntelligenceValidationException(
                "aliasName must contain 1 to 256 characters"
            );
        }
        if (aliasType == null || relation == null) {
            throw new PublicIntelligenceValidationException(
                "aliasType and relation are required"
            );
        }
        if (sourceEvidence == null || sourceEvidence.isBlank()) {
            throw new PublicIntelligenceValidationException(
                "sourceEvidence is required for a confirmed company alias"
            );
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new PublicIntelligenceValidationException("operatorId is required");
        }
    }
}
