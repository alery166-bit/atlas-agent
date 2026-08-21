package com.atlas.enterprise.company.port;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceResult;
import com.atlas.enterprise.company.SourceStatus;
import java.time.Instant;
import java.util.List;

public record CompanyRefreshResult(
    String refreshId,
    String providerName,
    Instant fetchedAt,
    SourceResult<CompanyFacts> facts,
    SourceResult<CompanyChange> changes,
    SourceResult<RiskEvent> riskEvents,
    List<SourceStatus> categoryStatuses
) {
    public CompanyRefreshResult {
        categoryStatuses = categoryStatuses == null ? List.of() : List.copyOf(categoryStatuses);
    }

    public boolean failed() {
        return facts == null
            || changes == null
            || riskEvents == null
            || facts.failed()
            || changes.failed()
            || riskEvents.failed();
    }

    public String firstFailureMessage() {
        return categoryStatuses.stream()
            .filter(SourceStatus::failed)
            .map(SourceStatus::message)
            .filter(message -> message != null && !message.isBlank())
            .findFirst()
            .orElse("Required company refresh category failed");
    }
}
