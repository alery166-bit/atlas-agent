package com.atlas.enterprise.company.port;

import com.atlas.enterprise.company.CompanyAlias;
import java.util.List;
import java.util.UUID;

public interface CompanyAliasRepository {
    CompanyAlias save(CompanyAlias alias);

    List<CompanyAlias> findByCompanyId(UUID atlasCompanyId);

    List<CompanyAlias> findConfirmedByCompanyId(UUID atlasCompanyId);
}
