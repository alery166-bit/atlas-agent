package com.atlas.enterprise.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure migration of the calculable rules in the legacy RiskScoreService. */
final class LegacyPureScoreCalculator {
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal EIGHT = new BigDecimal("8");

    Calculation calculate(LegacyRiskFeatures features) {
        return calculate(features, RiskScoringPolicy.defaultPolicy());
    }

    Calculation calculate(LegacyRiskFeatures features, RiskScoringPolicy policy) {
        List<RiskRuleHit> hits = new ArrayList<>();
        BigDecimal base = baseScore(features, hits, policy);
        BigDecimal label = riskLabelScore(features, hits, policy);
        BigDecimal industry = industryScore(features.industryIds());
        if (industry.signum() > 0) {
            hit(hits, "LEGACY_INDUSTRY_BASE", "旧模型行业基准分", null,
                industry, "INDUSTRY_SCORE", List.of(features.industryIds().toString()));
        }

        BigDecimal total;
        if (label.signum() > 0) {
            total = label.add(TEN.subtract(label).divide(TEN).multiply(base));
        } else {
            BigDecimal combined = industry.signum() > 0
                ? industry.add(TEN.subtract(industry).divide(TEN).multiply(base))
                : base;
            if (features.monitorCompany() && combined.compareTo(EIGHT) < 0
                && (features.relatedShareholderScore().signum() > 0
                    || features.relatedInvestmentScore().signum() > 0)) {
                combined = new BigDecimal("0.2").multiply(combined)
                    .add(new BigDecimal("0.5").multiply(features.relatedShareholderScore()))
                    .add(new BigDecimal("0.3").multiply(features.relatedInvestmentScore()));
                hit(hits, "LEGACY_RELATED_COMPANY_BLEND", "旧模型关联企业加权",
                    null, combined, "INTERMEDIATE_SCORE", List.of());
            }
            BigDecimal paidCapitalRate = paidCapitalRate(features.paidCapitalTenThousands());
            BigDecimal operatingYearsRate = operatingYearsRate(features.operatingYears());
            BigDecimal listingRate = listingRate(features.listingInfo());
            BigDecimal changeRate = features.corporateShareholdersChange()
                ? new BigDecimal("1.05")
                : BigDecimal.ONE;
            addRateHit(hits, "LEGACY_PAID_CAPITAL_RATE", "旧模型实缴资本倍率", paidCapitalRate);
            addRateHit(hits, "LEGACY_OPERATING_YEARS_RATE", "旧模型成立年限倍率", operatingYearsRate);
            addRateHit(hits, "LEGACY_LISTING_RATE", "旧模型上市情况倍率", listingRate);
            addRateHit(hits, "LEGACY_CHANGE_RATE", "旧模型工商变化倍率", changeRate);
            total = combined.multiply(paidCapitalRate)
                .multiply(operatingYearsRate)
                .multiply(listingRate)
                .multiply(changeRate);
        }

        BigDecimal uncompressed = total;
        while (total.compareTo(TEN) > 0) {
            total = total.multiply(new BigDecimal("0.95"));
        }
        if (uncompressed.compareTo(total) != 0) {
            hit(hits, "LEGACY_OVERFLOW_COMPRESSION", "旧模型超十分递减",
                null, total, "FINAL_ADJUSTMENT", List.of(uncompressed.toPlainString()));
        }
        return new Calculation(total.setScale(2, RoundingMode.HALF_DOWN), hits);
    }

    private static BigDecimal baseScore(
        LegacyRiskFeatures f,
        List<RiskRuleHit> hits,
        RiskScoringPolicy policy
    ) {
        BigDecimal total = BigDecimal.ZERO;
        if (f.sentimentKeywordCount() > 0 || f.complaintKeywordCount() > 5) {
            total = add(hits, total, "LEGACY_NEGATIVE_SENTIMENT_KEYWORD", "负面舆情关键词", RiskType.OTHER, policy.weight("LEGACY_NEGATIVE_SENTIMENT_KEYWORD", "3.0"));
        } else if (f.sentimentCount() > 0 || f.complaintKeywordCount() > 0) {
            total = add(hits, total, "LEGACY_NEGATIVE_SENTIMENT", "负面舆情", RiskType.OTHER, policy.weight("LEGACY_NEGATIVE_SENTIMENT", "2.4"));
        }
        if (f.complaintCount() > 0) {
            total = add(hits, total, "LEGACY_COMPLAINT", "投诉信息", RiskType.OFFLINE_COMPLAINT, policy.weight("LEGACY_COMPLAINT", "2.0"));
        }
        if (f.judicialKeywordCount() > 0) {
            total = add(hits, total, "LEGACY_JUDICIAL_KEYWORD", "司法文书敏感案由", RiskType.JUDICIAL_DOCUMENT, policy.weight("LEGACY_JUDICIAL_KEYWORD", "2.5"));
        } else if (f.judicialDefendantCount() > 0) {
            total = add(hits, total, "LEGACY_JUDICIAL_DEFENDANT", "司法文书被告/被执行", RiskType.JUDICIAL_DOCUMENT, policy.weight("LEGACY_JUDICIAL_DEFENDANT", "2.0"));
        }
        if (f.businessAbnormalCount() > 0) {
            total = add(hits, total, "LEGACY_BUSINESS_ABNORMAL", "经营异常", RiskType.BUSINESS_ABNORMAL, policy.weight("LEGACY_BUSINESS_ABNORMAL", "0.5"));
        }
        if (f.seriousIllegalCount() > 0) {
            total = add(hits, total, "LEGACY_SERIOUS_ILLEGAL", "严重违法", RiskType.SERIOUS_ILLEGAL, policy.weight("LEGACY_SERIOUS_ILLEGAL", "0.5"));
        }
        if (f.administrativePenaltyCount() > 0) {
            total = add(hits, total, "LEGACY_ADMINISTRATIVE_PENALTY", "行政处罚", RiskType.ADMINISTRATIVE_PENALTY, policy.weight("LEGACY_ADMINISTRATIVE_PENALTY", "0.5"));
        }
        if (f.equityPledgeCount() > 0) {
            total = add(hits, total, "LEGACY_EQUITY_PLEDGE", "股权出质", RiskType.EQUITY_PLEDGE, policy.weight("LEGACY_EQUITY_PLEDGE", "0.5"));
        } else if (f.stockPledgeCount() > 0) {
            total = add(hits, total, "LEGACY_STOCK_PLEDGE", "股权质押", RiskType.STOCK_PLEDGE, policy.weight("LEGACY_STOCK_PLEDGE", "0.5"));
        }
        if (f.equityFreezeCount() > 0) {
            total = add(hits, total, "LEGACY_EQUITY_FREEZE", "股权冻结", RiskType.EQUITY_FREEZE, policy.weight("LEGACY_EQUITY_FREEZE", "0.5"));
        }
        hit(hits, "LEGACY_BASE_TOTAL", "旧模型基础风险合计", null,
            total, "INTERMEDIATE_SCORE", List.of());
        return total;
    }

    private static BigDecimal riskLabelScore(
        LegacyRiskFeatures f,
        List<RiskRuleHit> hits,
        RiskScoringPolicy policy
    ) {
        Set<String> labels = f.riskLabelNos().stream()
            .filter(policy::legacyLabelEnabled)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        BigDecimal score = switch (f.scoringProfile()) {
            case STANDARD -> standardLabelScore(labels, f);
            case CHAOYANG -> chaoyangLabelScore(labels, f);
            case XIAN -> xianLabelScore(labels, f);
        };
        if (score.signum() > 0) {
            hit(hits, "LEGACY_RISK_LABEL_PRIORITY", "旧模型风险标签优先级",
                null, score, "RISK_LABEL_SCORE",
                List.of(f.scoringProfile().name(), labels.toString()));
        }
        return score;
    }

    private static BigDecimal standardLabelScore(Set<String> labels, LegacyRiskFeatures f) {
        if (has(labels, "104101101")) return decimal("6");
        if (hasAny(labels, "103104101", "103105101", "103106101", "103112120",
            "103112108", "103112102", "103112101", "103112107")) return decimal("9");
        if (hasAny(labels, "103109101", "103108101")) return decimal("8.5");
        if (hasAny(labels, "104102101", "103104102", "101102101", "101102103")) return decimal("8");
        if ((has(labels, "103107101") && !f.industryIds().contains(12L))
            || hasAny(labels, "103107102", "103107103", "103112112", "103107104", "103112111")) return decimal("7.5");
        if (has(labels, "103112122")) return decimal("7");
        if (has(labels, "103110101") && !has(labels, "102102102")) return decimal("5");
        if (has(labels, "103112104") || f.complaintKeywordCount() > 20) return decimal("4.5");
        if (hasAny(labels, "103101101", "103101102", "103112103", "103110102", "103112109")) return decimal("4");
        if (f.corporateShareholdersAddressChange()) return decimal("3");
        if (has(labels, "102105101")) return decimal("2.5");
        return BigDecimal.ZERO;
    }

    private static BigDecimal chaoyangLabelScore(Set<String> labels, LegacyRiskFeatures f) {
        if (hasAny(labels, "104101101", "102102105", "102101101", "101102101", "101102103")) return decimal("6");
        if (hasAny(labels, "103104101", "103105101", "103106101", "103112120",
            "103112108", "103112102", "103112101", "103112107")) return decimal("9");
        if (hasAny(labels, "103109101", "103108101")) return decimal("8.5");
        if (hasAny(labels, "104102101", "103104102")) return decimal("8");
        if ((has(labels, "103107101") && !f.industryIds().contains(12L))
            || hasAny(labels, "103107102", "103107103", "103112112", "103107104", "103112111")) return decimal("7.5");
        if (has(labels, "103112122")) return decimal("7");
        if ((has(labels, "103110101") && !has(labels, "102102102"))
            || has(labels, "101102103") || f.complaintKeywordCount() > 20) return decimal("5");
        if (hasAny(labels, "103112104", "101102101")) return decimal("4.5");
        if (hasAny(labels, "103101101", "103101102", "103112103", "103110102", "103112109")) return decimal("4");
        if (f.corporateShareholdersAddressChange()) return decimal("3");
        if (has(labels, "102105101")) return decimal("2.5");
        return BigDecimal.ZERO;
    }

    private static BigDecimal xianLabelScore(Set<String> labels, LegacyRiskFeatures f) {
        if (has(labels, "104101101")) return decimal("6");
        if (hasAny(labels, "103104101", "103105101", "103106101", "103112120",
            "103112108", "103112102", "103112101", "103112107")) return decimal("9");
        if (hasAny(labels, "103109101", "103108101")) return decimal("8.5");
        if (hasAny(labels, "104102101", "103104102")) return decimal("8");
        if ((has(labels, "103107101") && !f.industryIds().contains(12L))
            || hasAny(labels, "103107102", "103107103", "103112112", "103107104", "103112111")) return decimal("7.5");
        if (has(labels, "103112122")) return decimal("7");
        if ((has(labels, "103110101") && !has(labels, "102102102")) || has(labels, "101102103")) return decimal("5");
        if (hasAny(labels, "103112104", "101102101") || f.complaintKeywordCount() > 20) return decimal("4.5");
        if (hasAny(labels, "103101101", "103101102", "103112103", "103110102", "103112109")) return decimal("4");
        if (f.corporateShareholdersAddressChange()) return decimal("3");
        if (has(labels, "102105101")) return decimal("2.5");
        return BigDecimal.ZERO;
    }

    private static BigDecimal industryScore(Set<Long> ids) {
        if (ids.contains(13L)) return decimal("7");
        if (ids.contains(12L)) return decimal("6.4");
        if (containsAny(ids, 15L, 21L, 8L)) return decimal("3.5");
        if (containsAny(ids, 16L, 17L, 5L, 9L, 10L, 11L, 2L, 4L, 6L,
            7L, 3L, 28L, 26L, 48L)) return decimal("2");
        if (containsAny(ids, 33L, 19L, 20L)) return BigDecimal.ONE;
        return BigDecimal.ZERO;
    }

    private static BigDecimal paidCapitalRate(BigDecimal capital) {
        if (capital == null || capital.signum() <= 0) return BigDecimal.ONE;
        if (capital.compareTo(decimal("30000")) > 0) return decimal("0.95");
        if (capital.compareTo(decimal("20000")) > 0) return decimal("0.97");
        if (capital.compareTo(decimal("10000")) > 0) return decimal("0.98");
        if (capital.compareTo(decimal("8000")) > 0) return decimal("0.99");
        if (capital.compareTo(decimal("5000")) > 0) return BigDecimal.ONE;
        if (capital.compareTo(decimal("3000")) > 0) return decimal("1.02");
        if (capital.compareTo(decimal("1000")) > 0) return decimal("1.03");
        if (capital.compareTo(decimal("800")) > 0) return decimal("1.04");
        if (capital.compareTo(decimal("500")) > 0) return decimal("1.05");
        if (capital.compareTo(decimal("300")) > 0) return decimal("1.06");
        if (capital.compareTo(decimal("200")) > 0) return decimal("1.07");
        if (capital.compareTo(decimal("100")) > 0) return decimal("1.08");
        return decimal("1.09");
    }

    private static BigDecimal operatingYearsRate(Integer years) {
        if (years == null) return BigDecimal.ONE;
        if (years > 9) return decimal("0.95");
        if (years > 8) return decimal("0.97");
        if (years > 7) return decimal("0.98");
        if (years > 6) return decimal("0.99");
        if (years > 5) return BigDecimal.ONE;
        if (years > 4) return decimal("1.01");
        if (years > 3) return decimal("1.02");
        if (years > 2) return decimal("1.04");
        if (years > 1) return decimal("1.06");
        if (years > 0) return decimal("1.08");
        return BigDecimal.ONE;
    }

    private static BigDecimal listingRate(String listing) {
        if (listing == null) return BigDecimal.ONE;
        if (listing.contains("美股") || listing.contains("港股")) return decimal("1.05");
        if (listing.contains("A股") || listing.contains("三板")) return decimal("1.03");
        return BigDecimal.ONE;
    }

    private static BigDecimal add(List<RiskRuleHit> hits, BigDecimal total,
                                  String code, String name, RiskType type, BigDecimal value) {
        hit(hits, code, name, type, value, "BASE_SCORE", List.of());
        return total.add(value);
    }

    private static void addRateHit(List<RiskRuleHit> hits, String code, String name, BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ONE) != 0) {
            hit(hits, code, name, null, rate, "MULTIPLIER", List.of());
        }
    }

    private static void hit(List<RiskRuleHit> hits, String code, String name,
                            RiskType type, BigDecimal score, String role, List<String> refs) {
        hits.add(new RiskRuleHit(code, name, type, score, role, refs));
    }

    private static boolean has(Set<String> labels, String value) {
        return labels.contains(value);
    }

    private static boolean hasAny(Set<String> labels, String... values) {
        for (String value : values) if (labels.contains(value)) return true;
        return false;
    }

    private static boolean containsAny(Set<Long> values, Long... candidates) {
        for (Long candidate : candidates) if (values.contains(candidate)) return true;
        return false;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    record Calculation(BigDecimal score, List<RiskRuleHit> ruleHits) {
        Calculation {
            ruleHits = List.copyOf(ruleHits);
        }
    }
}
