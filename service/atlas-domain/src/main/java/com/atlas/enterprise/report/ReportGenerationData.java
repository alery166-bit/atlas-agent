package com.atlas.enterprise.report;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.risk.OperatorDecision;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import java.time.LocalDate;
import java.util.List;

public record ReportGenerationData(
    DataSnapshot dataSnapshot,
    RiskScoreSnapshot riskScore,
    PreviousReport templateBaseline,
    LocalDate reportDate,
    List<OperatorDecision> operatorDecisions,
    List<ReportEvidenceItem> confirmedEvidence
) {
    public ReportGenerationData {
        operatorDecisions = operatorDecisions == null
            ? List.of()
            : List.copyOf(operatorDecisions);
        confirmedEvidence = confirmedEvidence == null
            ? List.of()
            : List.copyOf(confirmedEvidence);
    }
}
