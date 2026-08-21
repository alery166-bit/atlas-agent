package com.atlas.enterprise.company.offline;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.CompanyQuery;
import com.atlas.enterprise.company.CompanyResolution;
import com.atlas.enterprise.company.CompanyResolutionStatus;
import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceResult;
import com.atlas.enterprise.company.SourceStatus;
import com.atlas.enterprise.company.port.CompanyDataProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnProperty(
    name = "atlas.data.provider",
    havingValue = "offline",
    matchIfMissing = true
)
public class OfflineCompanyDataProvider implements CompanyDataProvider {
    private final JsonCompanyDataProvider json;
    private final CsvCompanyDataProvider csv;

    public OfflineCompanyDataProvider(
        JsonCompanyDataProvider json,
        CsvCompanyDataProvider csv
    ) {
        this.json = json;
        this.csv = csv;
    }

    @Override
    public String providerName() {
        return "OFFLINE";
    }

    @Override
    public CompanyResolution resolve(CompanyQuery query) {
        CompanyResolution jsonResolution = json.resolve(query);
        if (hasUsableMatch(jsonResolution)) {
            return jsonResolution;
        }

        CompanyResolution csvResolution = csv.resolve(query);
        if (hasUsableMatch(csvResolution)) {
            return csvResolution;
        }

        List<SourceStatus> statuses = new ArrayList<>(jsonResolution.sourceStatuses());
        statuses.addAll(csvResolution.sourceStatuses());
        boolean allFailed = !statuses.isEmpty() && statuses.stream().allMatch(SourceStatus::failed);
        return new CompanyResolution(
            allFailed ? CompanyResolutionStatus.FAILED : CompanyResolutionStatus.NOT_FOUND,
            List.of(),
            statuses
        );
    }

    @Override
    public SourceResult<CompanyFacts> loadFacts(ResolvedCompany company) {
        return provider(company).loadFacts(company);
    }

    @Override
    public SourceResult<CompanyChange> loadChanges(ResolvedCompany company) {
        return provider(company).loadChanges(company);
    }

    @Override
    public SourceResult<RiskEvent> loadRiskEvents(ResolvedCompany company) {
        return provider(company).loadRiskEvents(company);
    }

    private CompanyDataProvider provider(ResolvedCompany company) {
        if (JsonCompanyDataProvider.SOURCE_SYSTEM.equals(company.sourceSystem())) {
            return json;
        }
        if (CsvCompanyDataProvider.SOURCE_SYSTEM.equals(company.sourceSystem())) {
            return csv;
        }
        throw new IllegalArgumentException("Unsupported offline source system: " + company.sourceSystem());
    }

    private static boolean hasUsableMatch(CompanyResolution resolution) {
        return resolution.status() == CompanyResolutionStatus.UNIQUE
            || resolution.status() == CompanyResolutionStatus.AMBIGUOUS;
    }
}
