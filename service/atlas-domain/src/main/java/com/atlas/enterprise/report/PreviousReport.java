package com.atlas.enterprise.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PreviousReport(
    boolean templateRecognized,
    double confidence,
    LocalDate reportDate,
    LocalDate dataAsOf,
    String companyName,
    Map<String, String> companyFields,
    BigDecimal originalRiskScore,
    BigDecimal manualRiskScore,
    String riskLevel,
    List<String> conclusions,
    Map<String, List<String>> reusableSections,
    List<String> unmappedLocations,
    PreviousReportType reportType,
    boolean supportedForUpdate,
    List<String> parseWarnings,
    String sourceContentSha256
) {
    public PreviousReport {
        companyFields = companyFields == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(companyFields));
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        reusableSections = reusableSections == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(reusableSections));
        unmappedLocations = unmappedLocations == null
            ? List.of()
            : List.copyOf(unmappedLocations);
        reportType = reportType == null ? PreviousReportType.UNKNOWN : reportType;
        parseWarnings = parseWarnings == null ? List.of() : List.copyOf(parseWarnings);
    }
}
