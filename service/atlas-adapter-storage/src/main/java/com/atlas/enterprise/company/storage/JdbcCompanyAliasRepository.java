package com.atlas.enterprise.company.storage;

import com.atlas.enterprise.company.CompanyAlias;
import com.atlas.enterprise.company.CompanyAliasRelation;
import com.atlas.enterprise.company.CompanyAliasType;
import com.atlas.enterprise.company.CompanyAliasVerificationStatus;
import com.atlas.enterprise.company.port.CompanyAliasRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCompanyAliasRepository implements CompanyAliasRepository {
    private final JdbcClient jdbc;

    public JdbcCompanyAliasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CompanyAlias save(CompanyAlias alias) {
        Optional<CompanyAlias> existing = jdbc.sql("""
                SELECT *
                  FROM company_alias
                 WHERE atlas_company_id = :companyId
                   AND alias_name = :aliasName
                   AND alias_type = :aliasType
                """)
            .param("companyId", alias.atlasCompanyId())
            .param("aliasName", alias.aliasName())
            .param("aliasType", alias.aliasType().name())
            .query(JdbcCompanyAliasRepository::map)
            .optional();
        if (existing.isPresent()) {
            CompanyAlias before = existing.get();
            if ("OPERATOR".equals(before.sourceSystem())
                && !"OPERATOR".equals(alias.sourceSystem())) {
                return before;
            }
            if (sameContent(before, alias)) {
                return before;
            }
            CompanyAlias updated = new CompanyAlias(
                before.aliasId(),
                alias.atlasCompanyId(),
                alias.aliasName(),
                alias.aliasType(),
                alias.relation(),
                alias.verificationStatus(),
                alias.sourceSystem(),
                alias.sourceRecordId(),
                alias.sourceEvidence(),
                alias.createdBy(),
                alias.validFrom(),
                alias.validTo(),
                before.createdAt(),
                alias.updatedAt()
            );
            update(updated);
            return updated;
        }
        jdbc.sql("""
                INSERT INTO company_alias (
                    alias_id, atlas_company_id, alias_name, alias_type,
                    relation_type, verification_status, source_system,
                    source_record_id, source_evidence, created_by,
                    valid_from, valid_to, created_at, updated_at
                ) VALUES (
                    :aliasId, :companyId, :aliasName, :aliasType,
                    :relationType, :verificationStatus, :sourceSystem,
                    :sourceRecordId, :sourceEvidence, :createdBy,
                    :validFrom, :validTo, :createdAt, :updatedAt
                )
                """)
            .param("aliasId", alias.aliasId())
            .param("companyId", alias.atlasCompanyId())
            .param("aliasName", alias.aliasName())
            .param("aliasType", alias.aliasType().name())
            .param("relationType", alias.relation().name())
            .param("verificationStatus", alias.verificationStatus().name())
            .param("sourceSystem", nullIfBlank(alias.sourceSystem()))
            .param("sourceRecordId", nullIfBlank(alias.sourceRecordId()))
            .param("sourceEvidence", nullIfBlank(alias.sourceEvidence()))
            .param("createdBy", nullIfBlank(alias.createdBy()))
            .param("validFrom", utc(alias.validFrom()))
            .param("validTo", utc(alias.validTo()))
            .param("createdAt", utc(alias.createdAt()))
            .param("updatedAt", utc(alias.updatedAt()))
            .update();
        return alias;
    }

    @Override
    public List<CompanyAlias> findByCompanyId(UUID atlasCompanyId) {
        return find(atlasCompanyId, false);
    }

    @Override
    public List<CompanyAlias> findConfirmedByCompanyId(UUID atlasCompanyId) {
        return find(atlasCompanyId, true);
    }

    private List<CompanyAlias> find(UUID atlasCompanyId, boolean confirmedOnly) {
        String statusClause = confirmedOnly
            ? " AND verification_status = 'CONFIRMED' AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP)"
            : "";
        return jdbc.sql("""
                SELECT *
                  FROM company_alias
                 WHERE atlas_company_id = :companyId
                """ + statusClause + " ORDER BY alias_type, alias_name")
            .param("companyId", atlasCompanyId)
            .query(JdbcCompanyAliasRepository::map)
            .list();
    }

    private void update(CompanyAlias alias) {
        jdbc.sql("""
                UPDATE company_alias
                   SET relation_type = :relationType,
                       verification_status = :verificationStatus,
                       source_system = :sourceSystem,
                       source_record_id = :sourceRecordId,
                       source_evidence = :sourceEvidence,
                       created_by = :createdBy,
                       valid_from = :validFrom,
                       valid_to = :validTo,
                       updated_at = :updatedAt
                 WHERE alias_id = :aliasId
                """)
            .param("relationType", alias.relation().name())
            .param("verificationStatus", alias.verificationStatus().name())
            .param("sourceSystem", nullIfBlank(alias.sourceSystem()))
            .param("sourceRecordId", nullIfBlank(alias.sourceRecordId()))
            .param("sourceEvidence", nullIfBlank(alias.sourceEvidence()))
            .param("createdBy", nullIfBlank(alias.createdBy()))
            .param("validFrom", utc(alias.validFrom()))
            .param("validTo", utc(alias.validTo()))
            .param("updatedAt", utc(alias.updatedAt()))
            .param("aliasId", alias.aliasId())
            .update();
    }

    private static CompanyAlias map(ResultSet rs, int rowNum) throws SQLException {
        return new CompanyAlias(
            rs.getObject("alias_id", UUID.class),
            rs.getObject("atlas_company_id", UUID.class),
            rs.getString("alias_name"),
            CompanyAliasType.valueOf(rs.getString("alias_type")),
            CompanyAliasRelation.valueOf(rs.getString("relation_type")),
            CompanyAliasVerificationStatus.valueOf(rs.getString("verification_status")),
            rs.getString("source_system"),
            rs.getString("source_record_id"),
            rs.getString("source_evidence"),
            rs.getString("created_by"),
            instant(rs, "valid_from"),
            instant(rs, "valid_to"),
            instant(rs, "created_at"),
            instant(rs, "updated_at")
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean sameContent(CompanyAlias left, CompanyAlias right) {
        return left.relation() == right.relation()
            && left.verificationStatus() == right.verificationStatus()
            && java.util.Objects.equals(left.sourceSystem(), right.sourceSystem())
            && java.util.Objects.equals(left.sourceRecordId(), right.sourceRecordId())
            && java.util.Objects.equals(left.sourceEvidence(), right.sourceEvidence())
            && java.util.Objects.equals(left.createdBy(), right.createdBy())
            && java.util.Objects.equals(left.validFrom(), right.validFrom())
            && java.util.Objects.equals(left.validTo(), right.validTo());
    }
}
