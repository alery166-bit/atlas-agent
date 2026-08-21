package com.atlas.enterprise.risk;

import java.time.Instant;
import java.util.UUID;

public record OperatorDecision(
    UUID decisionId,
    UUID taskId,
    String targetType,
    UUID targetId,
    String decisionType,
    String beforeJson,
    String afterJson,
    RiskAdjustmentReason reasonCode,
    String reasonText,
    String operatorId,
    Instant createdAt
) {
}
