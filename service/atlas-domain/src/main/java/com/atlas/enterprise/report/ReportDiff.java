package com.atlas.enterprise.report;

import java.util.List;

public record ReportDiff(
    List<ReportFieldChange> companyChanges,
    String previousReportDate,
    String currentReportDate,
    String previousRiskScore,
    String originalRiskScore,
    String manualRiskScore,
    int currentRiskEventCount,
    String summary,
    Integer previousReportVersionNo,
    int currentReportVersionNo,
    String previousOriginalRiskScore,
    String previousManualRiskScore,
    Integer previousRiskEventCount,
    String previousTemplateVersion,
    String currentTemplateVersion,
    List<ReportFieldChange> sectionChanges,
    List<ReportFieldChange> tableRowChanges,
    List<ReportFieldChange> conclusionChanges
) {
    public ReportDiff(
        List<ReportFieldChange> companyChanges,
        String previousReportDate,
        String currentReportDate,
        String previousRiskScore,
        String originalRiskScore,
        String manualRiskScore,
        int currentRiskEventCount,
        String summary,
        Integer previousReportVersionNo,
        int currentReportVersionNo,
        String previousOriginalRiskScore,
        String previousManualRiskScore,
        Integer previousRiskEventCount,
        String previousTemplateVersion,
        String currentTemplateVersion
    ) {
        this(companyChanges, previousReportDate, currentReportDate,
            previousRiskScore, originalRiskScore, manualRiskScore,
            currentRiskEventCount, summary, previousReportVersionNo,
            currentReportVersionNo, previousOriginalRiskScore,
            previousManualRiskScore, previousRiskEventCount,
            previousTemplateVersion, currentTemplateVersion,
            List.of(), List.of(), List.of());
    }

    public ReportDiff {
        companyChanges = companyChanges == null ? List.of() : List.copyOf(companyChanges);
        sectionChanges = sectionChanges == null ? List.of() : List.copyOf(sectionChanges);
        tableRowChanges = tableRowChanges == null ? List.of() : List.copyOf(tableRowChanges);
        conclusionChanges = conclusionChanges == null ? List.of() : List.copyOf(conclusionChanges);
    }
}
