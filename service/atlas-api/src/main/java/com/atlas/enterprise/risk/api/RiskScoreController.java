package com.atlas.enterprise.risk.api;

import com.atlas.enterprise.intelligence.application.PublicIntelligenceApplicationService;
import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskAssessmentRevision;
import com.atlas.enterprise.risk.application.RiskScoreAdjustmentResult;
import com.atlas.enterprise.risk.application.RiskScoreApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/risk-score")
public class RiskScoreController {
    private final RiskScoreApplicationService riskScores;
    private final PublicIntelligenceApplicationService publicIntelligence;

    public RiskScoreController(
        RiskScoreApplicationService riskScores,
        PublicIntelligenceApplicationService publicIntelligence
    ) {
        this.riskScores = riskScores;
        this.publicIntelligence = publicIntelligence;
    }

    /**
     * Calculates a score against the frozen structured snapshot.
     *
     * <p>W5 will call the same application service after public-intelligence
     * findings have been verified. Until then the endpoint accepts only
     * explicitly confirmed operator events; raw search text never triggers a
     * score floor.</p>
     */
    @PostMapping("/calculate")
    public RiskScoreResponse calculate(
        @PathVariable UUID taskId,
        @Valid @RequestBody(required = false) CalculateRiskScoreRequest request
    ) {
        List<ConfirmedRiskEventRequest> confirmedEvents = request == null
            ? List.of()
            : request.confirmedEvents();
        return RiskScoreResponse.from(riskScores.calculate(
            taskId,
            confirmedEvents.stream().map(ConfirmedRiskEventRequest::toDomain).toList()
        ));
    }

    @PostMapping("/calculate-from-confirmed-evidence")
    public RiskScoreResponse calculateFromConfirmedEvidence(
        @PathVariable UUID taskId
    ) {
        return RiskScoreResponse.from(riskScores.calculate(
            taskId,
            publicIntelligence.confirmedRiskEvents(taskId)
        ));
    }

    @GetMapping
    public RiskScoreResponse latest(@PathVariable UUID taskId) {
        return RiskScoreResponse.from(riskScores.latest(taskId));
    }

    @GetMapping("/history")
    public List<RiskAssessmentRevision> history(@PathVariable UUID taskId) {
        return riskScores.assessmentHistory(taskId);
    }

    @PostMapping("/{scoreSnapshotId}/adjustments")
    public RiskScoreAdjustmentResponse adjust(
        @PathVariable UUID taskId,
        @PathVariable UUID scoreSnapshotId,
        @Valid @RequestBody AdjustRiskScoreRequest request,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String operatorId
    ) {
        RiskScoreAdjustmentResult result = riskScores.adjust(
            taskId,
            scoreSnapshotId,
            request.manualScore(),
            request.reasonCode(),
            request.reasonText(),
            operatorId
        );
        return new RiskScoreAdjustmentResponse(
            RiskScoreResponse.from(result.score()),
            result.decision(),
            result.floorOverrideWarning()
        );
    }

    @GetMapping("/decisions")
    public List<OperatorDecision> decisions(@PathVariable UUID taskId) {
        return riskScores.decisions(taskId);
    }
}
