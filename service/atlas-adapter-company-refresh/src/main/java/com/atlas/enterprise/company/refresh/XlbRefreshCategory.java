package com.atlas.enterprise.company.refresh;

import java.util.Arrays;
import java.util.Locale;

enum XlbRefreshCategory {
    BASE(true),
    CONTACT(true),
    SHAREHOLDER(true),
    INVESTMENT(true),
    BRANCH(true),
    CHANGE(true),
    MAIN_PERSON(true),
    DISHONEST(true),
    EXECUTOR(true),
    LIMIT(true),
    JUDGEMENT(true),
    EQUITY_FREEZE(true),
    EQUITY_PLEDGE(true),
    EQUITY_HOSTAGE(true),
    FILING(true),
    ABNORMAL(true),
    ILLEGAL(true),
    ADMINISTRATIVE(true),
    ENVIRONMENT(true),
    CANCELLATION(true),
    LIQUIDATION(true),
    TAX_ILLEGAL(true),
    AUCTION(true),
    BANKRUPTCY(true),
    TAX_CREDIT(false),
    FINANCING(false),
    BUSINESS(false),
    CERTIFICATE(false),
    TRADEMARK(false),
    PATENT(false),
    COPYRIGHT(false);

    private final boolean reportRequired;

    XlbRefreshCategory(boolean reportRequired) {
        this.reportRequired = reportRequired;
    }

    boolean reportRequired() {
        return reportRequired;
    }

    static XlbRefreshCategory parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(category -> category.name().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown XLB refresh category: " + value));
    }
}
