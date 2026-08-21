package com.atlas.enterprise.intelligence.port;

import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewJob;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceSemanticReviewJobRepository {
    EvidenceSemanticReviewJob save(EvidenceSemanticReviewJob job);

    EvidenceSemanticReviewJob update(EvidenceSemanticReviewJob job);

    Optional<EvidenceSemanticReviewJob> findById(UUID reviewJobId);

    Optional<EvidenceSemanticReviewJob> findLatestByTaskId(UUID taskId);

    Optional<EvidenceSemanticReviewJob> findActiveByTaskId(UUID taskId);

    int failInterruptedJobs(Instant failedAt, String errorMessage);
}
