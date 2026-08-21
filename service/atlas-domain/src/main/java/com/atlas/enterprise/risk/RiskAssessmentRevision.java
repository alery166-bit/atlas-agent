package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only view of one complete system or operator assessment decision. */
public record RiskAssessmentRevision(
    UUID assessmentRevisionId,
    UUID taskId,
    UUID scoreSnapshotId,
    UUID dataSnapshotId,
    int revisionNo,
    RiskAssessmentTrigger triggerType,
    BigDecimal legacyScore,
    BigDecimal ruleCalculatedScore,
    BigDecimal eventFloorScore,
    BigDecimal originalScore,
    BigDecimal finalScore,
    RiskLevel originalRiskLevel,
    RiskLevel finalRiskLevel,
    String ruleVersion,
    String engineVersion,
    List<RiskAssessmentLabel> sourceLabels,
    List<RiskAssessmentLabel> ruleLabels,
    List<RiskAssessmentLabel> modelLabels,
    List<RiskAssessmentLabel> finalLabels,
    String actorType,
    String actorId,
    String reasonCode,
    String reasonText,
    Instant createdAt
) {
    public RiskAssessmentRevision {
        if (assessmentRevisionId == null || taskId == null || scoreSnapshotId == null
            || dataSnapshotId == null || revisionNo < 1 || triggerType == null
            || originalScore == null || finalScore == null
            || originalRiskLevel == null || finalRiskLevel == null
            || ruleVersion == null || ruleVersion.isBlank()
            || engineVersion == null || engineVersion.isBlank()
            || actorType == null || actorType.isBlank()
            || actorId == null || actorId.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Invalid risk assessment revision");
        }
        sourceLabels = sourceLabels == null ? List.of() : List.copyOf(sourceLabels);
        ruleLabels = ruleLabels == null ? List.of() : List.copyOf(ruleLabels);
        modelLabels = modelLabels == null ? List.of() : List.copyOf(modelLabels);
        finalLabels = finalLabels == null ? List.of() : List.copyOf(finalLabels);
        reasonCode = reasonCode == null ? "" : reasonCode;
        reasonText = reasonText == null ? "" : reasonText;
    }
}
