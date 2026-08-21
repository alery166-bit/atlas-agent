package com.atlas.enterprise.company.port;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.CompanyQuery;
import com.atlas.enterprise.company.CompanyResolution;
import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceResult;

public interface CompanyDataProvider {
    String providerName();

    CompanyResolution resolve(CompanyQuery query);

    SourceResult<CompanyFacts> loadFacts(ResolvedCompany company);

    SourceResult<CompanyChange> loadChanges(ResolvedCompany company);

    SourceResult<RiskEvent> loadRiskEvents(ResolvedCompany company);
}
