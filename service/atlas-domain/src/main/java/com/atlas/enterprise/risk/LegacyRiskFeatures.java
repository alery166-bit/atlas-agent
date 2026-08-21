package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Fully materialized input for the side-effect-free legacy calculator.
 */
public record LegacyRiskFeatures(
    boolean complete,
    LegacyScoringProfile scoringProfile,
    int sentimentCount,
    int sentimentKeywordCount,
    int complaintCount,
    int complaintKeywordCount,
    int judicialDefendantCount,
    int judicialKeywordCount,
    int businessAbnormalCount,
    int seriousIllegalCount,
    int administrativePenaltyCount,
    int equityPledgeCount,
    int equityFreezeCount,
    int stockPledgeCount,
    Set<Long> industryIds,
    Set<String> riskLabelNos,
    boolean corporateShareholdersChange,
    boolean corporateShareholdersAddressChange,
    BigDecimal paidCapitalTenThousands,
    Integer operatingYears,
    String listingInfo,
    boolean monitorCompany,
    BigDecimal relatedShareholderScore,
    BigDecimal relatedInvestmentScore
) {
    public LegacyRiskFeatures {
        scoringProfile = scoringProfile == null
            ? LegacyScoringProfile.STANDARD
            : scoringProfile;
        industryIds = industryIds == null ? Set.of() : Set.copyOf(industryIds);
        riskLabelNos = riskLabelNos == null ? Set.of() : Set.copyOf(riskLabelNos);
        listingInfo = listingInfo == null ? "" : listingInfo;
        relatedShareholderScore = zeroIfNull(relatedShareholderScore);
        relatedInvestmentScore = zeroIfNull(relatedInvestmentScore);
        if (sentimentCount < 0 || sentimentKeywordCount < 0
            || complaintCount < 0 || complaintKeywordCount < 0
            || judicialDefendantCount < 0 || judicialKeywordCount < 0
            || businessAbnormalCount < 0 || seriousIllegalCount < 0
            || administrativePenaltyCount < 0 || equityPledgeCount < 0
            || equityFreezeCount < 0 || stockPledgeCount < 0) {
            throw new IllegalArgumentException("Legacy risk feature counts must not be negative");
        }
    }

    public static LegacyRiskFeatures incomplete() {
        return new LegacyRiskFeatures(
            false,
            LegacyScoringProfile.STANDARD,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            Set.of(),
            Set.of(),
            false,
            false,
            null,
            null,
            "",
            false,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
