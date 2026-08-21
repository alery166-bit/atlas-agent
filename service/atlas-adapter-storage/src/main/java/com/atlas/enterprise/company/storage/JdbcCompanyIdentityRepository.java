package com.atlas.enterprise.company.storage;

import com.atlas.enterprise.company.AtlasCompanyIdentity;
import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.port.CompanyIdentityRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCompanyIdentityRepository implements CompanyIdentityRepository {
    private final JdbcClient jdbc;

    public JdbcCompanyIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AtlasCompanyIdentity bind(ResolvedCompany company, Instant now) {
        UUID companyId = findCompanyId(company).orElseGet(UUID::randomUUID);
        boolean exists = jdbc.sql("""
                SELECT COUNT(*)
                  FROM atlas_company
                 WHERE atlas_company_id = :companyId
                """)
            .param("companyId", companyId)
            .query(Integer.class)
            .single() > 0;

        if (exists) {
            jdbc.sql("""
                    UPDATE atlas_company
                       SET canonical_name = :canonicalName,
                           unified_credit_code = COALESCE(:creditCode, unified_credit_code),
                           registration_no = COALESCE(:registrationNo, registration_no),
                           updated_at = :updatedAt,
                           row_version = row_version + 1
                     WHERE atlas_company_id = :companyId
                    """)
                .param("canonicalName", company.canonicalName())
                .param("creditCode", blankToNull(company.unifiedCreditCode()))
                .param("registrationNo", blankToNull(company.registrationNo()))
                .param("updatedAt", utc(now))
                .param("companyId", companyId)
                .update();
        } else {
            jdbc.sql("""
                    INSERT INTO atlas_company (
                        atlas_company_id, canonical_name, unified_credit_code,
                        registration_no, created_at, updated_at
                    ) VALUES (
                        :companyId, :canonicalName, :creditCode,
                        :registrationNo, :createdAt, :updatedAt
                    )
                    """)
                .param("companyId", companyId)
                .param("canonicalName", company.canonicalName())
                .param("creditCode", blankToNull(company.unifiedCreditCode()))
                .param("registrationNo", blankToNull(company.registrationNo()))
                .param("createdAt", utc(now))
                .param("updatedAt", utc(now))
                .update();
        }

        upsertBinding(companyId, company, now);
        return new AtlasCompanyIdentity(companyId, company);
    }

    @Override
    public Optional<AtlasCompanyIdentity> findById(UUID atlasCompanyId) {
        return jdbc.sql("""
                SELECT c.atlas_company_id, c.canonical_name, c.unified_credit_code,
                       c.registration_no, b.source_system, b.source_entity_id
                  FROM atlas_company c
                  JOIN company_identity_binding b
                    ON b.atlas_company_id = c.atlas_company_id
                 WHERE c.atlas_company_id = :companyId
                   AND b.binding_status = 'CONFIRMED'
                 ORDER BY b.created_at
                 FETCH FIRST 1 ROW ONLY
                """)
            .param("companyId", atlasCompanyId)
            .query((rs, rowNum) -> new AtlasCompanyIdentity(
                rs.getObject("atlas_company_id", UUID.class),
                new ResolvedCompany(
                    rs.getString("source_system"),
                    rs.getString("source_entity_id"),
                    rs.getString("canonical_name"),
                    rs.getString("unified_credit_code"),
                    rs.getString("registration_no")
                )
            ))
            .optional();
    }

    private Optional<UUID> findCompanyId(ResolvedCompany company) {
        Optional<UUID> byBinding = jdbc.sql("""
                SELECT atlas_company_id
                  FROM company_identity_binding
                 WHERE source_system = :sourceSystem
                   AND source_entity_id = :sourceEntityId
                """)
            .param("sourceSystem", company.sourceSystem())
            .param("sourceEntityId", company.sourceEntityId())
            .query(UUID.class)
            .optional();
        if (byBinding.isPresent()) {
            return byBinding;
        }
        if (blankToNull(company.unifiedCreditCode()) == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT atlas_company_id
                  FROM atlas_company
                 WHERE unified_credit_code = :creditCode
                """)
            .param("creditCode", company.unifiedCreditCode())
            .query(UUID.class)
            .optional();
    }

    private void upsertBinding(UUID companyId, ResolvedCompany company, Instant now) {
        int updated = jdbc.sql("""
                UPDATE company_identity_binding
                   SET atlas_company_id = :companyId,
                       unified_credit_code = :creditCode,
                       confidence = 1.0000,
                       binding_status = 'CONFIRMED',
                       valid_to = NULL
                 WHERE source_system = :sourceSystem
                   AND source_entity_id = :sourceEntityId
                """)
            .param("companyId", companyId)
            .param("creditCode", blankToNull(company.unifiedCreditCode()))
            .param("sourceSystem", company.sourceSystem())
            .param("sourceEntityId", company.sourceEntityId())
            .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO company_identity_binding (
                        binding_id, atlas_company_id, source_system, source_entity_id,
                        legacy_md5, unified_credit_code, confidence, binding_status,
                        valid_from, created_at
                    ) VALUES (
                        :bindingId, :companyId, :sourceSystem, :sourceEntityId,
                        :legacyMd5, :creditCode, 1.0000, 'CONFIRMED',
                        :validFrom, :createdAt
                    )
                    """)
                .param("bindingId", UUID.randomUUID())
                .param("companyId", companyId)
                .param("sourceSystem", company.sourceSystem())
                .param("sourceEntityId", company.sourceEntityId())
                .param("legacyMd5", company.sourceEntityId())
                .param("creditCode", blankToNull(company.unifiedCreditCode()))
                .param("validFrom", utc(now))
                .param("createdAt", utc(now))
                .update();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
