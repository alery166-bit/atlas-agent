package com.atlas.enterprise.intelligence.api;

import com.atlas.enterprise.intelligence.EvidenceDecision;
import com.atlas.enterprise.intelligence.EvidenceContentSnapshot;
import com.atlas.enterprise.intelligence.PublicEvidence;
import com.atlas.enterprise.intelligence.PublicIntelligenceRun;
import com.atlas.enterprise.intelligence.SearchExecution;
import com.atlas.enterprise.intelligence.application.PublicIntelligenceApplicationService;
import com.atlas.enterprise.intelligence.application.EvidenceSemanticReviewJob;
import com.atlas.enterprise.intelligence.port.EvidenceSemanticReviewJobRunner;
import com.atlas.enterprise.risk.ConfirmedRiskEvent;
import com.atlas.enterprise.task.application.AutonomousTaskCompletionService;
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
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/tasks/{taskId}/public-intelligence")
public class PublicIntelligenceController {
    private final PublicIntelligenceApplicationService publicIntelligence;
    private final EvidenceSemanticReviewJobRunner semanticReviewJobs;
    private final AutonomousTaskCompletionService autonomousCompletion;

    public PublicIntelligenceController(
        PublicIntelligenceApplicationService publicIntelligence,
        EvidenceSemanticReviewJobRunner semanticReviewJobs,
        AutonomousTaskCompletionService autonomousCompletion
    ) {
        this.publicIntelligence = publicIntelligence;
        this.semanticReviewJobs = semanticReviewJobs;
        this.autonomousCompletion = autonomousCompletion;
    }

    @PostMapping("/search")
    public PublicIntelligenceRun search(@PathVariable UUID taskId) {
        return publicIntelligence.search(taskId);
    }

    @GetMapping("/searches")
    public List<SearchExecution> searches(@PathVariable UUID taskId) {
        return publicIntelligence.searches(taskId);
    }

    @GetMapping("/evidence")
    public List<PublicEvidence> evidence(@PathVariable UUID taskId) {
        return publicIntelligence.evidence(taskId);
    }

    @PostMapping("/evidence/model-review")
    public ResponseEntity<EvidenceSemanticReviewJob> modelReview(
        @PathVariable UUID taskId
    ) {
        return ResponseEntity.accepted().body(semanticReviewJobs.start(taskId));
    }

    @GetMapping("/evidence/model-review/jobs/latest")
    public ResponseEntity<EvidenceSemanticReviewJob> latestModelReview(
        @PathVariable UUID taskId
    ) {
        return ResponseEntity.of(semanticReviewJobs.latest(taskId));
    }

    @PostMapping("/evidence/model-review/jobs/{reviewJobId}/cancel")
    public EvidenceSemanticReviewJob cancelModelReview(
        @PathVariable UUID taskId,
        @PathVariable UUID reviewJobId
    ) {
        return semanticReviewJobs.cancel(taskId, reviewJobId);
    }

    @PostMapping("/evidence/{evidenceId}/decision")
    public EvidenceDecision decide(
        @PathVariable UUID taskId,
        @PathVariable UUID evidenceId,
        @Valid @RequestBody EvidenceDecisionRequest request,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String operatorId
    ) {
        EvidenceDecision saved = publicIntelligence.decide(
            taskId,
            evidenceId,
            request.decision(),
            request.reason(),
            operatorId
        );
        autonomousCompletion.completeIfReady(taskId);
        return saved;
    }

    @GetMapping("/decisions")
    public List<EvidenceDecision> decisions(@PathVariable UUID taskId) {
        return publicIntelligence.decisions(taskId);
    }

    @PostMapping("/evidence/{evidenceId}/content-snapshot")
    public EvidenceContentSnapshot captureContent(
        @PathVariable UUID taskId,
        @PathVariable UUID evidenceId
    ) {
        return publicIntelligence.captureContent(taskId, evidenceId);
    }

    @GetMapping("/evidence/{evidenceId}/content-snapshot")
    public EvidenceContentSnapshot latestContentSnapshot(
        @PathVariable UUID taskId,
        @PathVariable UUID evidenceId
    ) {
        return publicIntelligence.latestContentSnapshot(taskId, evidenceId);
    }

    @GetMapping("/content-snapshots")
    public List<EvidenceContentSnapshot> contentSnapshots(
        @PathVariable UUID taskId
    ) {
        return publicIntelligence.contentSnapshots(taskId);
    }

    @GetMapping("/confirmed-events")
    public List<ConfirmedRiskEvent> confirmedEvents(@PathVariable UUID taskId) {
        return publicIntelligence.confirmedRiskEvents(taskId);
    }
}
