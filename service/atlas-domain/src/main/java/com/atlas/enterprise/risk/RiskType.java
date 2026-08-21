package com.atlas.enterprise.risk;

import java.util.Arrays;
import java.util.Optional;

/**
 * Versioned canonical risk dictionary.
 *
 * <p>The legacy label number and index name are retained for migration and
 * traceability. New risk types do not need to invent an Elasticsearch index.</p>
 */
public enum RiskType {
    SERIOUS_ILLEGAL("102101105", "严重违法", "sentiment_company_violationlaw_online_v1"),
    ADMINISTRATIVE_PENALTY("102101102", "行政处罚", "sentiment_company_punish_online_v1"),
    JUDGMENT_DEBTOR("101102102", "被执行人", "sentiment_company_executor_online_v1"),
    JUDICIAL_DOCUMENT("101101101", "被起诉", "sentiment_company_document_online_v1"),
    DISHONEST("101102101", "失信人", "sentiment_company_dishonest_online_v1"),
    BUSINESS_ABNORMAL("102101101", "经营异常", "sentiment_company_abnormal_online_v1"),
    LIQUIDATION("102102103", "清算", "sentiment_company_liquidation_online_v1"),
    BANKRUPTCY("102102104", "破产", "sentiment_company_bankruptcy_online_v1"),
    EQUITY_PLEDGE("102103101", "股权出质", "sentiment_company_pledge_online_v1"),
    EQUITY_FREEZE("102103103", "股权冻结", "sentiment_company_stockfreeze_online_v1"),
    STOCK_PLEDGE("102103102", "股权质押", "sentiment_company_stockpledge_online_v1"),
    ENVIRONMENTAL_PENALTY("102101103", "环保处罚", "sentiment_company_environmentpunish_online_v1"),
    JUDICIAL_AUCTION("101101102", "司法拍卖", "sentiment_company_judicialauction_online_v1"),
    LIMIT_CONSUMPTION("101102103", "限制高消费", "sentiment_company_limitconsumption_online_v1"),
    TAX_ILLEGAL("102101104", "税收违法", "sentiment_company_taxviolation_online_v1"),
    SIMPLE_CANCELLATION("102102105", "简易注销", "sentiment_company_simple_cancel_online_v1"),
    CANCELLATION("102102102", "注销", ""),
    REVOKED("102102101", "吊销", ""),
    PUBLIC_SECURITY_CASE("104101101", "公安立案", ""),
    RELATED_PUBLIC_SECURITY_CASE("104102101", "关联公安立案", ""),
    BUSINESS_CRISIS("103102101", "暴雷", ""),
    RELATED_BUSINESS_CRISIS("103102102", "关联暴雷", ""),
    LICENSE_CANCELLATION("103110101", "牌照注销", ""),
    OFFLINE_COMPLAINT("103112110", "12345投诉", ""),
    WEBSITE_ABNORMAL("102105101", "官网异常", ""),
    REMOTE_OPERATION("102104101", "异地经营", ""),
    ILLEGAL_FUNDRAISING("103104104", "非法集资", ""),
    SUSPECTED_ILLEGAL_FUNDRAISING("103104101", "(疑似)非法集资", ""),
    WAGE_ARREARS("103112113", "拖欠工资", ""),
    DEBT_DEFAULT("103112104", "债务违约", ""),
    PYRAMID_SELLING("103105102", "传销", ""),
    SUSPECTED_PYRAMID_SELLING("103105101", "(疑似)传销", ""),
    FRAUD("103106102", "诈骗", ""),
    SUSPECTED_FRAUD("103106101", "(疑似)诈骗", ""),
    CLEARANCE("103104103", "清退", ""),
    ROMANCE_SCAM("103112108", "杀猪盘", ""),
    GAMBLING("103112102", "博彩", ""),
    MONEY_LAUNDERING("103112101", "洗钱", ""),
    OUT_OF_CONTACT("103112107", "失联", ""),
    STORE_CLOSURE(null, "门店关闭", ""),
    OTHER(null, "其他", "");

    private final String legacyLabelNo;
    private final String displayName;
    private final String legacyIndexName;

    RiskType(String legacyLabelNo, String displayName, String legacyIndexName) {
        this.legacyLabelNo = legacyLabelNo;
        this.displayName = displayName;
        this.legacyIndexName = legacyIndexName;
    }

    public String legacyLabelNo() {
        return legacyLabelNo;
    }

    public String displayName() {
        return displayName;
    }

    public String legacyIndexName() {
        return legacyIndexName;
    }

    public static Optional<RiskType> fromLegacyLabelNo(String labelNo) {
        return Arrays.stream(values())
            .filter(type -> type.legacyLabelNo != null && type.legacyLabelNo.equals(labelNo))
            .findFirst();
    }

    public static RiskType fromCanonicalName(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        return switch (value.trim().toUpperCase()) {
            case "LAW_INFO" -> SERIOUS_ILLEGAL;
            case "PUNISH" -> ADMINISTRATIVE_PENALTY;
            case "EXECUTOR" -> JUDGMENT_DEBTOR;
            case "ENFORCEMENT" -> JUDGMENT_DEBTOR;
            case "DOCUMENT", "JUDGEMENT", "JUDGMENT", "CASE_FILING" -> JUDICIAL_DOCUMENT;
            case "ABNORMAL" -> BUSINESS_ABNORMAL;
            case "GQ_PLEDGE" -> EQUITY_PLEDGE;
            case "GQ_FREEZE" -> EQUITY_FREEZE;
            case "GQ_ZHIYA", "EQUITY_HOSTAGE" -> STOCK_PLEDGE;
            case "ENVIRONMENTAL" -> ENVIRONMENTAL_PENALTY;
            case "JUDICIAL_SALE" -> JUDICIAL_AUCTION;
            case "CONSUMPTION" -> LIMIT_CONSUMPTION;
            case "TAXATION" -> TAX_ILLEGAL;
            case "CANCEL_LICENSE" -> SIMPLE_CANCELLATION;
            case "SIMPLE_CANCEL_LICENSE" -> CANCELLATION;
            case "REVOKE_LICENSE" -> REVOKED;
            case "GONG_AN" -> PUBLIC_SECURITY_CASE;
            case "GONG_AN_RELATION" -> RELATED_PUBLIC_SECURITY_CASE;
            case "BAO_LEI" -> BUSINESS_CRISIS;
            case "BAO_LEI_RELATION" -> RELATED_BUSINESS_CRISIS;
            case "LICENSE_CANCEL" -> LICENSE_CANCELLATION;
            case "OFFLINE_TOUSU" -> OFFLINE_COMPLAINT;
            case "SITE_STATE" -> WEBSITE_ABNORMAL;
            case "ADDR_REMOTE" -> REMOTE_OPERATION;
            case "ILLEGAL_FUNDS" -> ILLEGAL_FUNDRAISING;
            case "ILLEGAL_FUNDS_SUSPECTED" -> SUSPECTED_ILLEGAL_FUNDRAISING;
            case "UNREACHABLE", "OUT_OF_CONTACT", "OUT_OF_CANTACT" -> OUT_OF_CONTACT;
            case "BACKPAY", "WAGE_ARREARS" -> WAGE_ARREARS;
            case "ZHAIWU_WEIYUE" -> DEBT_DEFAULT;
            case "PYRAMID_SELLING_SUSPECTED" -> SUSPECTED_PYRAMID_SELLING;
            case "SWINDLE" -> FRAUD;
            case "SWINDLE_SUSPECTED" -> SUSPECTED_FRAUD;
            case "CLEAR_AWAY" -> CLEARANCE;
            case "SPECIAL_SWINDLE" -> ROMANCE_SCAM;
            case "MONEY_LAUNDER" -> MONEY_LAUNDERING;
            case "CLOSURE", "STORE_CLOSURE" -> STORE_CLOSURE;
            default -> {
                try {
                    yield valueOf(value.trim().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    yield OTHER;
                }
            }
        };
    }
}
