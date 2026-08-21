package com.atlas.enterprise.intelligence.port;

import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewJob;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceSemanticReviewJobRunner {
    EvidenceSemanticReviewJob start(UUID taskId);

    Optional<EvidenceSemanticReviewJob> latest(UUID taskId);

    EvidenceSemanticReviewJob cancel(UUID taskId, UUID reviewJobId);
}
