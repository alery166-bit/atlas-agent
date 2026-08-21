package com.atlas.enterprise.report.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.ReportEvidenceItem;
import com.atlas.enterprise.report.ReportGenerationData;
import com.atlas.enterprise.risk.RiskLevel;
import com.atlas.enterprise.risk.RiskRuleHit;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.RiskType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class OoxmlReportTemplateRendererTest {

    @Test
    void exposesRendererVersionThatInvalidatesCachedReportsAfterLayoutChanges() {
        assertEquals("atlas-ooxml-renderer/1.3.1", new OoxmlReportTemplateRenderer().rendererVersion());
    }
    private final OoxmlPreviousReportParser parser = new OoxmlPreviousReportParser();
    private final OoxmlReportTemplateRenderer renderer = new OoxmlReportTemplateRenderer();

    @Test
    void parsesFormalTemplateWithHighConfidence() throws Exception {
        byte[] template = Files.readAllBytes(templatePath());

        PreviousReport previous = parser.parse(template);

        assertTrue(previous.templateRecognized());
        assertTrue(previous.confidence() >= 0.80d);
        assertEquals("北京简熹和食品有限公司", previous.companyName());
        assertEquals(LocalDate.of(2026, 7, 14), previous.reportDate());
        assertEquals("91110113MAK5DEJQ0W",
            previous.companyFields().get("统一社会信用代码"));
        assertEquals(new BigDecimal("0.0"), previous.originalRiskScore());
    }

    @Test
    void updatesControlledSlotsAndPreservesOpaquePackageParts() throws Exception {
        byte[] template = Files.readAllBytes(templatePath());
        String templateHash = OoxmlDocxSupport.sha256(template);
        PreviousReport previous = parser.parse(template);
        Instant asOf = Instant.parse("2026-07-30T00:00:00Z");
        UUID taskId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        CompanyFacts facts = new CompanyFacts(
            "虚构测试科技有限公司",
            "TEST-CREDIT-CODE-001",
            "TEST-REG-001",
            "测试负责人甲",
            "存续",
            "测试市测试区测试路1号",
            "其他有限责任公司",
            "5000万元人民币",
            "2009年6月5日",
            "北京市朝阳区市场监督管理局",
            "技术服务；数据处理和存储支持服务",
            "{\"国标行业中类\":\"食品、饮料及烟草制品专门零售\","
                + "\"国标行业大类\":\"零售业\","
                + "\"国标行业小类\":\"其他食品零售\","
                + "\"国标行业门类\":\"批发和零售业\"}",
            "TEST",
            "company-1",
            asOf,
            asOf,
            Map.ofEntries(
                Map.entry("organization_code", "MAK5DEJQ-0"),
                Map.entry("approved_date", "2026-07-30"),
                Map.entry("region", "北京市顺义区"),
                Map.entry("former_names", "[]"),
                Map.entry("actualController", "测试负责人甲"),
                Map.entry("officialWebsites", "[{\"value\":\"https://corp.example.invalid\"}]"),
                Map.entry("websites", "[\"https://cloud.example.invalid\"]"),
                Map.entry("shareholders", """
                    [{"name":"测试负责人甲","ratio":"44%","registeredAmount":"2200"},
                     {"name":"虚构投资企业甲","ratio":"34%","registeredAmount":"1700"},
                     {"name":"虚构投资企业乙","ratio":"17%","registeredAmount":"850"},
                     {"name":"虚构投资企业丙","ratio":"5%","registeredAmount":"250"}]
                    """),
                Map.entry("keyPersonnel", """
                    [{"type":"MAIN_PERSON","name":"测试负责人甲","position":"经理,董事,财务负责人"},
                     {"type":"CORE_PERSON","name":"测试负责人甲","position":"经理,董事,财务负责人","brief":"虚构测试企业创始人"},
                     {"type":"MAIN_PERSON","name":"测试人员乙","position":"监事"}]
                    """),
                Map.entry("outboundInvestments", """
                    [{"name":"虚构被投企业甲","registeredAmount":"1000","ratio":"100%","status":"在营"}]
                    """),
                Map.entry("branches", """
                    [{"name":"虚构测试科技有限公司测试分公司","legalRepresentative":"测试负责人甲","status":"在营（开业）"}]
                    """)
            )
        );
        DataSnapshot snapshot = new DataSnapshot(
            snapshotId,
            taskId,
            UUID.randomUUID(),
            2,
            facts,
            List.of(
                new CompanyChange(
                    "change-1", "住所变更", "2026-07-30",
                    "北京市朝阳区", facts.registeredAddress(),
                    "TEST", asOf, asOf, Map.of()
                ),
                new CompanyChange(
                    "change-2", "经营范围变更", "2026-07-30",
                    "食品销售", facts.businessScope(),
                    "TEST", asOf, asOf, Map.of()
                )
            ),
            List.of(new RiskEvent(
                "ADMINISTRATIVE_PENALTY",
                "TEST",
                "信用中国",
                "penalty-1",
                "2026-07-29",
                "行政处罚记录",
                "测试风险摘要",
                asOf,
                asOf,
                Map.of()
            )),
            List.of(),
            "snapshot-hash",
            asOf
        );
        RiskScoreSnapshot score = new RiskScoreSnapshot(
            UUID.randomUUID(),
            taskId,
            snapshotId,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("8"),
            new BigDecimal("8"),
            new BigDecimal("4.5"),
            RiskLevel.HIGH,
            RiskLevel.MEDIUM,
            "RISK_RULES_V1",
            "atlas-risk-engine/1.0.0",
            "score-hash",
            List.of(new RiskRuleHit(
                "EVENT_FLOOR_STORE_CLOSURE",
                "门店关闭最低分",
                RiskType.STORE_CLOSURE,
                new BigDecimal("8"),
                "FLOOR",
                List.of("evidence-1")
            )),
            asOf
        );
        ReportDocument source = new ReportDocument(
            templatePath().toString(),
            "V1-" + templateHash.substring(0, 12),
            templateHash,
            template
        );

        byte[] output = renderer.render(
            source,
            new ReportGenerationData(
                snapshot,
                score,
                previous,
                LocalDate.of(2026, 7, 30),
                List.of(),
                List.of(new ReportEvidenceItem(
                    UUID.randomUUID(),
                    RiskType.STORE_CLOSURE,
                    "门店暂停营业，现场已撤除招牌",
                    "运营人员抓取并核验的网页正文显示，该企业门店已经停止营业。".repeat(30),
                    "primary-web-search",
                    "https://news.example.com/store-closed",
                    "news.example.com",
                    Instant.parse("2026-07-28T08:00:00Z"),
                    Instant.parse("2026-07-30T08:00:00Z"),
                    UUID.randomUUID(),
                    "content-snapshot-hash",
                    false
                ))
            )
        );
        assertNotEquals(templateHash, OoxmlDocxSupport.sha256(output));
        Map<String, byte[]> sourceParts = OoxmlDocxSupport.unzip(template);
        Map<String, byte[]> outputParts = OoxmlDocxSupport.unzip(output);
        assertEquals(sourceParts.keySet(), outputParts.keySet());
        for (String name : sourceParts.keySet()) {
            if (!name.equals("word/document.xml") && !name.equals("word/settings.xml")) {
                assertEquals(
                    OoxmlDocxSupport.sha256(sourceParts.get(name)),
                    OoxmlDocxSupport.sha256(outputParts.get(name)),
                    "preserve-only part changed: " + name
                );
            }
        }
        Document document = OoxmlDocxSupport.parse(outputParts.get("word/document.xml"));
        String text = document.getDocumentElement().getTextContent();
        assertTrue(text.contains("原始分 8（高风险）；人工分 4.5（中风险）"));
        assertTrue(text.contains("门店关闭"));
        assertTrue(text.contains("排查结论：截至2026年07月30日，发现需关注的风险信息"));
        assertTrue(text.contains("纳入1条负面公开证据"));
        assertTrue(text.contains("门店暂停营业，现场已撤除招牌"));
        assertTrue(text.contains("运营人员抓取并核验的网页正文"));
        assertTrue(new String(
            outputParts.get("word/document.xml"), StandardCharsets.UTF_8
        ).contains("https://news.example.com/store-closed"));
        assertTrue(text.contains("本次公开检索未纳入经研判确认的互联网投诉证据"));
        assertTrue(text.contains("住所变更"));
        assertTrue(text.contains("行政处罚记录"));
        assertTrue(text.contains("2026年07月30日"));
        assertTrue(text.contains("其他食品零售"));
        assertTrue(text.contains("零售业"));
        assertTrue(!text.contains("国标行业小类"));
        assertTrue(!text.contains("[]"));
        assertTrue(!text.contains("北京简熹和食品有限公司"));
        assertTrue(!text.contains("葛雪"));
        assertTrue(text.contains("疑似实际控制人为测试负责人甲"));
        assertTrue(text.contains("股东及持股信息详见本报告股东信息表"));
        assertTrue(!text.contains("暂未监测到完整股权穿透比例信息"));
        assertTrue(text.contains("虚构投资企业甲"));
        assertTrue(text.contains("https://corp.example.invalid"));
        assertTrue(text.contains("https://cloud.example.invalid"));
        assertTrue(text.contains("虚构测试企业创始人"));
        assertTrue(text.contains("虚构被投企业甲"));
        assertTrue(text.contains("虚构测试科技有限公司测试分公司"));
        assertTrue(!text.contains("表2 股东信息"));
        assertTrue(!text.contains("表4法人关联企业情况"));
        assertTrue(!text.contains("表5实控人关联企业情况"));
        Element shareholderHeading = OoxmlDocxSupport.bodyParagraphs(document).stream()
            .filter(paragraph -> OoxmlDocxSupport.text(paragraph).contains("2、股东信息"))
            .findFirst()
            .orElseThrow();
        assertEquals(0, shareholderHeading.getElementsByTagNameNS(
            OoxmlDocxSupport.W, "cr"
        ).getLength());
        assertEquals(0, shareholderHeading.getElementsByTagNameNS(
            OoxmlDocxSupport.W, "br"
        ).getLength());
        Element companyTable = OoxmlDocxSupport.bodyTables(document).stream()
            .filter(table -> OoxmlDocxSupport.text(table).contains("统一社会信用代码"))
            .findFirst()
            .orElseThrow();
        assertTrue(OoxmlDocxSupport.text(companyTable).contains("实缴资本/"));
        assertTrue(OoxmlDocxSupport.text(companyTable).contains("纳税人识别号/"));
        assertTrue(!OoxmlDocxSupport.text(companyTable).contains("未公示"));
        Element shareholderTable = OoxmlDocxSupport.bodyTables(document).stream()
            .filter(table -> OoxmlDocxSupport.text(table).contains("股东信息持股比例"))
            .findFirst()
            .orElseThrow();
        assertEquals(5, OoxmlDocxSupport.rows(shareholderTable).size());
        assertTrue(OoxmlDocxSupport.firstDescendant(
            OoxmlDocxSupport.rows(shareholderTable).getFirst(), "tblHeader"
        ) != null);
        Element changeTable = OoxmlDocxSupport.bodyTables(document).stream()
            .filter(table -> OoxmlDocxSupport.text(table).contains("住所变更"))
            .findFirst()
            .orElseThrow();
        assertEquals(3, OoxmlDocxSupport.rows(changeTable).size());
        Element riskTable = OoxmlDocxSupport.bodyTables(document).stream()
            .filter(table -> OoxmlDocxSupport.text(table).contains("行政处罚记录"))
            .findFirst()
            .orElseThrow();
        assertEquals(2, OoxmlDocxSupport.rows(riskTable).size());
        assertTrue(OoxmlDocxSupport.text(riskTable).contains("风险事项"));
        assertTrue(OoxmlDocxSupport.text(riskTable).contains("主要内容"));
        assertTrue(OoxmlDocxSupport.text(riskTable).contains("行政监管公开信息"));
        assertTrue(OoxmlDocxSupport.firstDescendant(
            OoxmlDocxSupport.rows(riskTable).getFirst(), "tblHeader"
        ) != null);
        Element evidenceTable = OoxmlDocxSupport.bodyTables(document).stream()
            .filter(table -> OoxmlDocxSupport.text(table).contains("舆情事项"))
            .findFirst()
            .orElseThrow();
        assertEquals(2, OoxmlDocxSupport.rows(evidenceTable).size());
        assertTrue(OoxmlDocxSupport.text(evidenceTable).contains("内容摘要"));
        assertTrue(OoxmlDocxSupport.text(evidenceTable).contains("信息来源"));
        assertTrue(OoxmlDocxSupport.text(evidenceTable).contains("news.example.com"));
        assertTrue(OoxmlDocxSupport.text(evidenceTable).contains("查看原文"));
        assertTrue(OoxmlDocxSupport.firstDescendant(
            OoxmlDocxSupport.rows(evidenceTable).getFirst(), "tblHeader"
        ) != null);
        Element evidenceRow = OoxmlDocxSupport.rows(evidenceTable).get(1);
        assertTrue(OoxmlDocxSupport.firstDescendant(evidenceRow, "cantSplit") != null);
        String renderedExcerpt = OoxmlDocxSupport.text(
            OoxmlDocxSupport.cells(evidenceRow).get(3)
        );
        assertTrue(renderedExcerpt.length() <= 181);
        assertTrue(renderedExcerpt.endsWith("…"));
        assertTrue(text.contains("计分风险标签："));
        assertTrue(text.contains("历史记录是否计分取决于事件类型、有效时间和当前规则版本"));

        DataSnapshot emptyChangeSnapshot = new DataSnapshot(
            snapshotId,
            taskId,
            snapshot.atlasCompanyId(),
            snapshot.snapshotVersion(),
            facts,
            List.of(new CompanyChange(
                "empty-change-record",
                "无法记录",
                "2026-07-28",
                "/",
                "/",
                "elasticsearch",
                asOf,
                asOf,
                Map.of()
            )),
            snapshot.riskEvents(),
            snapshot.sourceStatuses(),
            "empty-change-snapshot-hash",
            asOf
        );
        byte[] emptyOutput = renderer.render(
            source,
            new ReportGenerationData(
                emptyChangeSnapshot,
                score,
                previous,
                LocalDate.of(2026, 7, 30),
                List.of(),
                List.of()
            )
        );
        Document emptyDocument = OoxmlDocxSupport.parse(
            OoxmlDocxSupport.unzip(emptyOutput).get("word/document.xml")
        );
        String emptyText = emptyDocument.getDocumentElement().getTextContent();
        assertEquals(
            OoxmlDocxSupport.bodyTables(document).size() - 2,
            OoxmlDocxSupport.bodyTables(emptyDocument).size()
        );
        assertTrue(emptyText.contains(
            "暂未监测到相关信息，不排除信息未对外公示，信息披露滞后等情况。"
        ));
        assertTrue(!emptyText.contains("无法记录"));
        assertTrue(!emptyText.contains("表3 工商变更信息"));
        List<Element> emptyParagraphs = OoxmlDocxSupport.bodyParagraphs(emptyDocument);
        int changeHeading = -1;
        for (int index = 0; index < emptyParagraphs.size(); index++) {
            if (OoxmlDocxSupport.text(emptyParagraphs.get(index)).contains("6、工商变更")) {
                changeHeading = index;
                break;
            }
        }
        assertTrue(changeHeading >= 0);
        assertTrue(OoxmlDocxSupport.text(emptyParagraphs.get(changeHeading + 1))
            .contains("暂未监测到相关信息"));
        assertTrue(OoxmlDocxSupport.text(emptyParagraphs.get(changeHeading + 2))
            .contains("7、对外投资控股企业"));

        DataSnapshot noRiskSnapshot = new DataSnapshot(
            UUID.randomUUID(),
            taskId,
            snapshot.atlasCompanyId(),
            snapshot.snapshotVersion(),
            facts,
            List.of(),
            List.of(),
            snapshot.sourceStatuses(),
            "no-risk-snapshot-hash",
            asOf
        );
        RiskScoreSnapshot noRiskScore = new RiskScoreSnapshot(
            UUID.randomUUID(),
            taskId,
            noRiskSnapshot.snapshotId(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            RiskLevel.LOW,
            RiskLevel.LOW,
            "RISK_RULES_V1",
            "atlas-risk-engine/1.0.0",
            "no-risk-score-hash",
            List.of(),
            asOf
        );
        byte[] noRiskOutput = renderer.render(
            source,
            new ReportGenerationData(
                noRiskSnapshot,
                noRiskScore,
                previous,
                LocalDate.of(2026, 7, 30),
                List.of(),
                List.of()
            )
        );
        String noRiskText = OoxmlDocxSupport.parse(
            OoxmlDocxSupport.unzip(noRiskOutput).get("word/document.xml")
        ).getDocumentElement().getTextContent();
        assertTrue(noRiskText.contains(
            "排查结论：截至2026年07月30日，暂未监测到明确风险信息"
        ));
        assertTrue(noRiskText.contains(
            "公开检索已完成，本报告未纳入经研判确认的负面公开证据"
        ));
        assertTrue(noRiskText.contains(
            "暂未监测到相关信息，不排除信息未对外公示，信息披露滞后等情况。"
        ));
        assertTrue(noRiskText.contains("暂无（历史风险记录详见正文）"));
        assertFalse(noRiskText.contains("未发现该项结构化公开记录"));

        for (String filename : List.of(
            "北京万企成业管理顾问咨询有限公司_企业风险监测分析报告20260730.docx",
            "北京美时悦美容科技有限公司_企业风险监测分析报告20260729.docx"
        )) {
            Path historicalPath = historicalReportPath(filename);
            byte[] historicalBytes = Files.readAllBytes(historicalPath);
            PreviousReport historicalReport = parser.parse(historicalBytes);
            byte[] historicalOutput = renderer.render(
                source,
                new ReportGenerationData(
                    noRiskSnapshot,
                    noRiskScore,
                    historicalReport,
                    LocalDate.of(2026, 8, 11),
                    List.of(),
                    List.of()
                )
            );
            String historicalText = OoxmlDocxSupport.parse(
                OoxmlDocxSupport.unzip(historicalOutput).get("word/document.xml")
            ).getDocumentElement().getTextContent();
            assertTrue(historicalText.contains("2026年08月11日"), filename);
            assertTrue(historicalText.contains("暂未监测到明确风险信息"), filename);
            assertTrue(!historicalText.contains("北京简熹和食品有限公司"), filename);
        }
        assertEquals(templateHash, OoxmlDocxSupport.sha256(
            Files.readAllBytes(templatePath())
        ));
    }

    private static Path templatePath() {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(Path.of(
                "data",
                "templates",
                "北京简熹和食品有限公司_企业风险监测分析报告20260714.docx"
            ));
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException("Formal V1 DOCX template was not found");
    }

    private static Path historicalReportPath(String filename) {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(Path.of(
                "data", "historical-reports", "incoming", filename
            ));
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException("Historical report was not found: " + filename);
    }
}
