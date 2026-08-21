package com.atlas.enterprise.risk.port;

import com.atlas.enterprise.risk.RiskAssessmentRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRevisionRepository {
    RiskAssessmentRevision save(RiskAssessmentRevision revision);

    List<RiskAssessmentRevision> findByTaskId(UUID taskId);

    Optional<RiskAssessmentRevision> findLatestByTaskId(UUID taskId);

    Optional<RiskAssessmentRevision> findSystemRevisionByScoreSnapshotId(UUID scoreSnapshotId);

    int nextRevisionNo(UUID taskId);
}
