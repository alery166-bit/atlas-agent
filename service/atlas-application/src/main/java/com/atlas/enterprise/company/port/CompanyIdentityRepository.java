package com.atlas.enterprise.company.port;

import com.atlas.enterprise.company.AtlasCompanyIdentity;
import com.atlas.enterprise.company.ResolvedCompany;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CompanyIdentityRepository {
    AtlasCompanyIdentity bind(ResolvedCompany company, Instant now);

    Optional<AtlasCompanyIdentity> findById(UUID atlasCompanyId);
}
