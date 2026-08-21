package com.atlas.enterprise.company.port;

import com.atlas.enterprise.company.ResolvedCompany;

/**
 * Refreshes the structured company dataset before Atlas freezes a task snapshot.
 */
public interface CompanyRefreshPort {
    boolean enabled();

    CompanyRefreshResult refresh(ResolvedCompany company);

    default CompanyRefreshResult refresh(ResolvedCompany company, Runnable heartbeat) {
        return refresh(company);
    }
}
