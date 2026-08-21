package com.atlas.enterprise.intelligence.port;

import com.atlas.enterprise.intelligence.EvidenceDecision;
import com.atlas.enterprise.intelligence.EvidenceContentSnapshot;
import com.atlas.enterprise.intelligence.EvidenceContentReference;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.SearchExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicIntelligenceRepository {
    SearchExecution saveSearch(SearchExecution search);

    PublicEvidence saveEvidence(PublicEvidence evidence);

    PublicEvidence updateEvidence(PublicEvidence evidence);

    EvidenceDecision saveDecision(EvidenceDecision decision);

    EvidenceContentSnapshot saveContentSnapshot(EvidenceContentSnapshot snapshot);

    List<SearchExecution> findSearchesByTaskId(UUID taskId);

    List<PublicEvidence> findEvidenceByTaskId(UUID taskId);

    List<PublicEvidence> findEvidenceByTaskIds(List<UUID> taskIds);

    Optional<PublicEvidence> findEvidenceById(UUID evidenceId);

    List<EvidenceDecision> findDecisionsByTaskId(UUID taskId);

    List<EvidenceContentSnapshot> findContentSnapshotsByTaskId(UUID taskId);

    List<EvidenceContentReference> findContentReferencesByTaskId(UUID taskId);

    List<EvidenceContentReference> findContentReferencesByTaskIds(
        List<UUID> taskIds
    );

    Optional<EvidenceContentSnapshot> findLatestContentSnapshot(UUID evidenceId);
}
