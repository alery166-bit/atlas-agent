package com.atlas.enterprise.report.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.PreviousReportType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class OoxmlHistoricalReportParserTest {
    private final OoxmlPreviousReportParser parser = new OoxmlPreviousReportParser();

    @Test
    void classifiesTheReceivedHistoricalBatchWithoutSilentFallback() throws Exception {
        List<Path> files;
        try (var stream = Files.list(historicalRoot())) {
            files = stream
                .filter(path -> path.getFileName().toString().endsWith(".docx"))
                .sorted()
                .toList();
        }

        assertEquals(13, files.size());
        List<PreviousReport> reports = files.stream()
            .map(OoxmlHistoricalReportParserTest::read)
            .map(parser::parse)
            .toList();

        assertEquals(6, reports.stream()
            .filter(report -> report.reportType()
                == PreviousReportType.STANDARD_RISK_REPORT)
            .count());
        assertEquals(7, reports.stream()
            .filter(report -> report.reportType()
                == PreviousReportType.NEW_REGISTRATION_BACKGROUND_REVIEW)
            .count());
        assertEquals(0, reports.stream()
            .filter(report -> report.reportType() == PreviousReportType.UNKNOWN)
            .count());
        String diagnostics = java.util.stream.IntStream.range(0, files.size())
            .mapToObj(index -> files.get(index).getFileName()
                + " => " + reports.get(index).reportType()
                + ", confidence=" + reports.get(index).confidence()
                + ", warnings=" + reports.get(index).parseWarnings())
            .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(
            6,
            reports.stream().filter(PreviousReport::supportedForUpdate).count(),
            diagnostics
        );
        assertEquals(13, reports.stream().map(PreviousReport::sourceContentSha256)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new)).size());

        for (PreviousReport report : reports) {
            assertNotNull(report.companyName());
            assertNotNull(report.reportDate());
            assertEquals(64, report.sourceContentSha256().length());
            if (report.reportType() == PreviousReportType.STANDARD_RISK_REPORT) {
                assertTrue(report.templateRecognized());
                assertTrue(report.confidence() >= 0.80d);
                assertNotNull(report.originalRiskScore());
                assertNotNull(report.companyFields().get("统一社会信用代码"));
            } else {
                assertFalse(report.supportedForUpdate());
                assertTrue(report.parseWarnings().stream().anyMatch(
                    warning -> warning.startsWith("REPORT_TYPE_UNSUPPORTED:")
                ));
            }
        }
    }

    @Test
    void extractsNarrativeRiskLevelButDoesNotInventANumericScore() throws Exception {
        PreviousReport report = parser.parse(Files.readAllBytes(historicalRoot().resolve(
            "北京依月芳华健康咨询服务有限公司_企业风险监测分析报告20260727.docx"
        )));

        assertEquals(PreviousReportType.NEW_REGISTRATION_BACKGROUND_REVIEW, report.reportType());
        assertEquals("北京依月芳华健康咨询服务有限公司", report.companyName());
        assertEquals("低风险", report.riskLevel());
        assertNull(report.originalRiskScore());
        assertFalse(report.conclusions().isEmpty());
    }

    @Test
    void keepsTheStandardHistoricalScoreAndSourceFingerprint() throws Exception {
        PreviousReport report = parser.parse(Files.readAllBytes(historicalRoot().resolve(
            "北京万企成业管理顾问咨询有限公司_企业风险监测分析报告20260730.docx"
        )));

        assertEquals(PreviousReportType.STANDARD_RISK_REPORT, report.reportType());
        assertTrue(report.supportedForUpdate());
        assertEquals(new BigDecimal("0.0"), report.originalRiskScore());
        assertEquals("91110105MA01R2QT3X", report.companyFields().get("统一社会信用代码"));
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path historicalRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path folder = candidate.resolve(Path.of("data", "historical-reports", "incoming"));
            if (Files.isDirectory(folder)) return folder;
        }
        throw new IllegalStateException("Historical report intake folder was not found");
    }
}
