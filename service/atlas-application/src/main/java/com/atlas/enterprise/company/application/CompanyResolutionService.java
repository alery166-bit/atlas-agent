package com.atlas.enterprise.company.application;

import com.atlas.enterprise.company.CompanyQuery;
import com.atlas.enterprise.company.CompanyResolution;
import com.atlas.enterprise.company.port.CompanyDataProvider;
import org.springframework.stereotype.Service;

@Service
public class CompanyResolutionService {
    private final CompanyDataProvider companyDataProvider;

    public CompanyResolutionService(CompanyDataProvider companyDataProvider) {
        this.companyDataProvider = companyDataProvider;
    }

    public CompanyResolution resolve(String query) {
        return companyDataProvider.resolve(new CompanyQuery(query));
    }
}
