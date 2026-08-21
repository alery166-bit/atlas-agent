package com.atlas.enterprise.acceptance.api;

import com.atlas.enterprise.acceptance.application.GoldenAcceptanceService;
import com.atlas.enterprise.acceptance.application.GoldenAcceptanceService.CaseEvaluation;
import com.atlas.enterprise.acceptance.application.GoldenAcceptanceService.SuiteDetail;
import com.atlas.enterprise.acceptance.application.GoldenAcceptanceService.SuiteSummary;
import com.atlas.enterprise.acceptance.port.GoldenAcceptanceRepository.Run;
import com.atlas.enterprise.acceptance.port.GoldenAcceptanceRepository.Suite;
import com.fasterxml.jackson.databind.JsonNode;
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
@RequestMapping("/api/platform/golden-acceptance")
public class GoldenAcceptanceController {
    private final GoldenAcceptanceService acceptance;

    public GoldenAcceptanceController(GoldenAcceptanceService acceptance) {
        this.acceptance = acceptance;
    }

    @PostMapping("/suites")
    public Suite importSuite(
        @RequestBody ImportSuiteRequest request,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String headerOperator
    ) {
        return acceptance.importSuite(
            request.name(), request.manifest(), operator(request.operatorId(), headerOperator)
        );
    }

    @GetMapping("/suites")
    public List<SuiteSummary> suites() {
        return acceptance.list();
    }

    @GetMapping("/suites/{suiteId}")
    public SuiteDetail suite(@PathVariable UUID suiteId) {
        return acceptance.get(suiteId);
    }

    @PostMapping("/suites/{suiteId}/evaluations")
    public Run evaluate(
        @PathVariable UUID suiteId,
        @RequestBody EvaluationRequest request,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator") String headerOperator
    ) {
        return acceptance.evaluate(
            suiteId, request.cases(), operator(request.operatorId(), headerOperator)
        );
    }

    private static String operator(String requested, String header) {
        return requested == null || requested.isBlank() ? header : requested;
    }

    public record ImportSuiteRequest(String name, JsonNode manifest, String operatorId) {
    }

    public record EvaluationRequest(List<CaseEvaluation> cases, String operatorId) {
    }
}
