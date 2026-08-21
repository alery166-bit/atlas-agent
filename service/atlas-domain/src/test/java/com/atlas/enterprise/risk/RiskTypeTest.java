package com.atlas.enterprise.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskTypeTest {

    @Test
    void mapsLegacyMisspellingToOutOfContact() {
        assertEquals(RiskType.OUT_OF_CONTACT, RiskType.fromCanonicalName("OUT_OF_CANTACT"));
        assertEquals(RiskType.OUT_OF_CONTACT, RiskType.fromCanonicalName("unreachable"));
        assertEquals(RiskType.OUT_OF_CONTACT, RiskType.fromCanonicalName("out_of_contact"));
    }

    @Test
    void mapsEveryRenamedLegacyRiskEnumConstant() {
        assertLegacyAlias("LAW_INFO", RiskType.SERIOUS_ILLEGAL);
        assertLegacyAlias("PUNISH", RiskType.ADMINISTRATIVE_PENALTY);
        assertLegacyAlias("EXECUTOR", RiskType.JUDGMENT_DEBTOR);
        assertLegacyAlias("DOCUMENT", RiskType.JUDICIAL_DOCUMENT);
        assertLegacyAlias("ABNORMAL", RiskType.BUSINESS_ABNORMAL);
        assertLegacyAlias("GQ_PLEDGE", RiskType.EQUITY_PLEDGE);
        assertLegacyAlias("GQ_FREEZE", RiskType.EQUITY_FREEZE);
        assertLegacyAlias("GQ_ZHIYA", RiskType.STOCK_PLEDGE);
        assertLegacyAlias("ENVIRONMENTAL", RiskType.ENVIRONMENTAL_PENALTY);
        assertLegacyAlias("JUDICIAL_SALE", RiskType.JUDICIAL_AUCTION);
        assertLegacyAlias("CONSUMPTION", RiskType.LIMIT_CONSUMPTION);
        assertLegacyAlias("TAXATION", RiskType.TAX_ILLEGAL);
        assertLegacyAlias("CANCEL_LICENSE", RiskType.SIMPLE_CANCELLATION);
        assertLegacyAlias("SIMPLE_CANCEL_LICENSE", RiskType.CANCELLATION);
        assertLegacyAlias("REVOKE_LICENSE", RiskType.REVOKED);
        assertLegacyAlias("GONG_AN", RiskType.PUBLIC_SECURITY_CASE);
        assertLegacyAlias("GONG_AN_RELATION", RiskType.RELATED_PUBLIC_SECURITY_CASE);
        assertLegacyAlias("BAO_LEI", RiskType.BUSINESS_CRISIS);
        assertLegacyAlias("BAO_LEI_RELATION", RiskType.RELATED_BUSINESS_CRISIS);
        assertLegacyAlias("LICENSE_CANCEL", RiskType.LICENSE_CANCELLATION);
        assertLegacyAlias("OFFLINE_TOUSU", RiskType.OFFLINE_COMPLAINT);
        assertLegacyAlias("SITE_STATE", RiskType.WEBSITE_ABNORMAL);
        assertLegacyAlias("ADDR_REMOTE", RiskType.REMOTE_OPERATION);
        assertLegacyAlias("ILLEGAL_FUNDS", RiskType.ILLEGAL_FUNDRAISING);
        assertLegacyAlias("ILLEGAL_FUNDS_SUSPECTED", RiskType.SUSPECTED_ILLEGAL_FUNDRAISING);
        assertLegacyAlias("BACKPAY", RiskType.WAGE_ARREARS);
        assertLegacyAlias("ZHAIWU_WEIYUE", RiskType.DEBT_DEFAULT);
        assertLegacyAlias("PYRAMID_SELLING_SUSPECTED", RiskType.SUSPECTED_PYRAMID_SELLING);
        assertLegacyAlias("SWINDLE", RiskType.FRAUD);
        assertLegacyAlias("SWINDLE_SUSPECTED", RiskType.SUSPECTED_FRAUD);
        assertLegacyAlias("CLEAR_AWAY", RiskType.CLEARANCE);
        assertLegacyAlias("SPECIAL_SWINDLE", RiskType.ROMANCE_SCAM);
        assertLegacyAlias("MONEY_LAUNDER", RiskType.MONEY_LAUNDERING);
    }

    @Test
    void resolvesEveryLegacyLabelNumber() {
        for (RiskType type : RiskType.values()) {
            if (type.legacyLabelNo() != null) {
                assertEquals(type, RiskType.fromLegacyLabelNo(type.legacyLabelNo()).orElseThrow());
            }
        }
    }

    @Test
    void unknownOrEmptyValuesDoNotBreakScoring() {
        assertEquals(RiskType.OTHER, RiskType.fromCanonicalName(null));
        assertEquals(RiskType.OTHER, RiskType.fromCanonicalName("  "));
        assertEquals(RiskType.OTHER, RiskType.fromCanonicalName("not-a-risk-type"));
        assertTrue(RiskType.fromLegacyLabelNo("unknown").isEmpty());
    }

    private static void assertLegacyAlias(String legacyName, RiskType expected) {
        assertEquals(expected, RiskType.fromCanonicalName(legacyName));
        assertEquals(expected, RiskType.fromCanonicalName("  " + legacyName.toLowerCase() + "  "));
    }
}
