package com.atlas.enterprise.company;

import java.util.UUID;

public record AtlasCompanyIdentity(
    UUID atlasCompanyId,
    ResolvedCompany resolvedCompany
) {
}
