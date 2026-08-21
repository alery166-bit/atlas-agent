package com.atlas.enterprise.report.docx;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.ReportEvidenceItem;
import com.atlas.enterprise.report.ReportGenerationData;
import com.atlas.enterprise.report.application.ReportValidationException;
import com.atlas.enterprise.report.port.ReportTemplateRenderer;
import com.atlas.enterprise.risk.RiskLevel;
import com.atlas.enterprise.risk.RiskRuleHit;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.RiskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Component
public class OoxmlReportTemplateRenderer implements ReportTemplateRenderer {
    private static final String VERSION = "atlas-ooxml-renderer/1.3.1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CN_DATE =
        DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final String NOT_DISCLOSED = "/";
    private static final String NO_RELEVANT_INFORMATION =
        "暂未监测到相关信息，不排除信息未对外公示，信息披露滞后等情况。";
    private static final Pattern JSON_STRING_FIELD = Pattern.compile(
        "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\""
    );
    private static final Pattern JSON_STRING_ITEM = Pattern.compile(
        "\"((?:\\\\.|[^\"])*)\""
    );

    private static final Map<String, RiskType> RISK_SECTIONS = Map.ofEntries(
        Map.entry("异地经营", RiskType.REMOTE_OPERATION),
        Map.entry("牌照注销", RiskType.LICENSE_CANCELLATION),
        Map.entry("经营异常", RiskType.BUSINESS_ABNORMAL),
        Map.entry("严重违法", RiskType.SERIOUS_ILLEGAL),
        Map.entry("股权出质", RiskType.EQUITY_PLEDGE),
        Map.entry("股权质押", RiskType.STOCK_PLEDGE),
        Map.entry("行政处罚", RiskType.ADMINISTRATIVE_PENALTY),
        Map.entry("环保处罚", RiskType.ENVIRONMENTAL_PENALTY),
        Map.entry("税收违法", RiskType.TAX_ILLEGAL),
        Map.entry("清算信息", RiskType.LIQUIDATION),
        Map.entry("吊销、注销", RiskType.CANCELLATION),
        Map.entry("被执行情况", RiskType.JUDGMENT_DEBTOR),
        Map.entry("失信被执行情况", RiskType.DISHONEST),
        Map.entry("限制高消费情况", RiskType.LIMIT_CONSUMPTION),
        Map.entry("被起诉情况", RiskType.JUDICIAL_DOCUMENT),
        Map.entry("破产重整", RiskType.BANKRUPTCY),
        Map.entry("司法拍卖", RiskType.JUDICIAL_AUCTION),
        Map.entry("股权冻结", RiskType.EQUITY_FREEZE),
        Map.entry("立案信息", RiskType.PUBLIC_SECURITY_CASE)
    );

    @Override
    public String rendererVersion() {
        return VERSION;
    }

    @Override
    public byte[] render(ReportDocument template, ReportGenerationData data) {
        Map<String, byte[]> parts = OoxmlDocxSupport.unzip(template.content());
        Document document = OoxmlDocxSupport.parse(parts.get("word/document.xml"));
        List<Element> paragraphs = OoxmlDocxSupport.bodyParagraphs(document);
        List<Element> tables = OoxmlDocxSupport.bodyTables(document);
        if (tables.size() < 3) {
            throw new ReportValidationException(
                "V1 template must contain the basic, shareholder and change tables"
            );
        }

        CompanyFacts facts = data.dataSnapshot().companyFacts();
        replaceCover(paragraphs, data.templateBaseline().companyName(), facts.canonicalName());
        replaceReportDate(paragraphs, data.templateBaseline().reportDate(), data.reportDate());
        replaceRiskSummary(paragraphs, data, template.fieldMapping());
        replaceCompanyTable(tables.get(0), facts, template.fieldMapping());
        Element shareholderPrototype = OoxmlDocxSupport.cloneElement(tables.get(1));
        Element fiveColumnPrototype = OoxmlDocxSupport.cloneElement(tables.get(2));
        replaceBasicRelationSections(
            paragraphs,
            tables.get(1),
            shareholderPrototype,
            fiveColumnPrototype,
            facts
        );
        if (tables.size() >= 5) {
            replaceUnsupportedRelatedAppendices(
                paragraphs, tables.get(3), tables.get(4)
            );
        }
        Element riskTablePrototype = OoxmlDocxSupport.cloneElement(tables.get(2));
        replaceChangeSection(paragraphs, tables.get(2), data.dataSnapshot());
        replaceStructuredRiskSections(
            paragraphs,
            data.dataSnapshot(),
            riskTablePrototype
        );
        replacePublicEvidenceSections(
            paragraphs,
            data.confirmedEvidence(),
            template.fieldMapping(),
            riskTablePrototype
        );

        parts.put("word/document.xml", OoxmlDocxSupport.serialize(document));
        parts.put("word/settings.xml", updateFields(parts.get("word/settings.xml")));
        byte[] output = OoxmlDocxSupport.zip(parts);
        requireCoreText(output, facts, data.riskScore(), data.reportDate());
        return output;
    }

    private static void replaceCover(
        List<Element> paragraphs,
        String previousName,
        String currentName
    ) {
        Element company = findExact(paragraphs, previousName);
        if (company == null) {
            int title = findContainingIndex(paragraphs, "风险监测分析报告");
            company = previousNonBlank(paragraphs, title);
        }
        if (company == null) {
            throw new ReportValidationException("Could not locate cover company name");
        }
        OoxmlDocxSupport.setText(company, required(currentName));
    }

    private static void replaceReportDate(
        List<Element> paragraphs,
        LocalDate previousDate,
        LocalDate currentDate
    ) {
        Element date = previousDate == null
            ? null
            : firstMatching(paragraphs, flexibleChineseDatePattern(previousDate));
        if (date == null) {
            date = firstMatching(
                paragraphs,
                "\\s*20\\d{2}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日\\s*"
            );
        }
        if (date == null) {
            throw new ReportValidationException("Could not locate cover report date");
        }
        OoxmlDocxSupport.setText(date, currentDate.format(CN_DATE));
    }

    private static String flexibleChineseDatePattern(LocalDate date) {
        return "\\s*%d\\s*年\\s*%s\\s*月\\s*%s\\s*日\\s*".formatted(
            date.getYear(),
            flexibleDateNumber(date.getMonthValue()),
            flexibleDateNumber(date.getDayOfMonth())
        );
    }

    private static String flexibleDateNumber(int value) {
        return value < 10 ? "0?" + value : Integer.toString(value);
    }

    private static void replaceRiskSummary(
        List<Element> paragraphs,
        ReportGenerationData data,
        Map<String, String> fieldMapping
    ) {
        RiskScoreSnapshot score = data.riskScore();
        setNextParagraph(
            paragraphs,
            mapping(fieldMapping, "risk_score", "风险分："),
            scoreText(score)
        );
        String riskLabelHeading = mapping(fieldMapping, "risk_level", "风险标签：");
        setNextParagraph(
            paragraphs,
            riskLabelHeading,
            riskTags(data)
        );
        Element riskLabel = findExact(paragraphs, riskLabelHeading);
        if (riskLabel != null) {
            OoxmlDocxSupport.setText(riskLabel, "计分风险标签：");
        }

        int summaryIndex = findExactIndex(paragraphs, "总结：");
        if (summaryIndex < 0) {
            throw new ReportValidationException("Could not locate report summary slot");
        }
        List<Element> slots = nextNonBlank(paragraphs, summaryIndex, 3);
        if (slots.size() < 3) {
            throw new ReportValidationException("V1 summary requires three source paragraphs");
        }
        CompanyFacts facts = data.dataSnapshot().companyFacts();
        String companySummary = "%s成立于%s，注册资本%s，注册地址%s，法定代表人为%s，当前登记状态为%s。"
            .formatted(
                required(facts.canonicalName()),
                value(facts.establishedDate()),
                value(facts.registeredCapital()),
                value(facts.registeredAddress()),
                value(facts.legalRepresentative()),
                value(facts.registrationStatus())
            );
        OoxmlDocxSupport.setText(slots.get(0), companySummary);
        OoxmlDocxSupport.setText(
            slots.get(1),
            "经营范围：" + value(facts.businessScope()) + "。"
        );
        String asOf = dataAsOf(data.dataSnapshot()).format(CN_DATE);
        String evidenceSummary = data.confirmedEvidence().isEmpty()
            ? "公开检索已完成，本报告未纳入经研判确认的负面公开证据"
            : "公开检索证据经Atlas自动研判或人工复核后，本报告纳入%d条负面公开证据"
                .formatted(data.confirmedEvidence().size());
        boolean noConfirmedRisk = data.dataSnapshot().riskEvents().isEmpty()
            && data.confirmedEvidence().isEmpty();
        String conclusion = noConfirmedRisk
            ? "排查结论：截至%s，暂未监测到明确风险信息"
                .formatted(asOf)
            : "排查结论：截至%s，发现需关注的风险信息，详见本报告相关章节"
                .formatted(asOf);
        String monitoring = "%s。本次更新基于已冻结的企业结构化数据，共纳入%d条结构化风险记录，评分规则命中%d项；历史记录是否计分取决于事件类型、有效时间和当前规则版本。%s。"
            .formatted(
                conclusion,
                data.dataSnapshot().riskEvents().size(),
                scoringHitCount(data.riskScore()),
                evidenceSummary
            );
        OoxmlDocxSupport.setText(slots.get(2), monitoring);
    }

    private static void replacePublicEvidenceSections(
        List<Element> paragraphs,
        List<ReportEvidenceItem> evidence,
        Map<String, String> fieldMapping,
        Element tablePrototype
    ) {
        List<ReportEvidenceItem> complaints = evidence.stream()
            .filter(item -> item.riskType() == RiskType.WAGE_ARREARS)
            .toList();
        List<ReportEvidenceItem> publicSentiment = evidence.stream()
            .filter(item -> item.riskType() != RiskType.WAGE_ARREARS)
            .toList();
        replacePublicEvidenceSection(
            paragraphs,
            mapping(fieldMapping, "public_evidence", "网络舆情"),
            publicSentiment,
            "本次公开检索未纳入经研判确认的网络舆情证据。",
            tablePrototype
        );
        replacePublicEvidenceSection(
            paragraphs,
            "互联网投诉",
            complaints,
            "本次公开检索未纳入经研判确认的互联网投诉证据。",
            tablePrototype
        );
    }

    private static void replacePublicEvidenceSection(
        List<Element> paragraphs,
        String headingText,
        List<ReportEvidenceItem> evidence,
        String emptyText,
        Element tablePrototype
    ) {
        Element heading = findNumberedHeading(paragraphs, headingText);
        if (heading == null) {
            throw new ReportValidationException(
                "Could not locate public evidence section: " + headingText
            );
        }
        Element content = nextNonBlankElement(paragraphs, paragraphs.indexOf(heading));
        if (content == null || isHeading(content)) {
            throw new ReportValidationException(
                "Could not locate public evidence content slot: " + headingText
            );
        }
        if (evidence.isEmpty()) {
            OoxmlDocxSupport.setText(content, "    " + emptyText);
            return;
        }
        replaceEvidenceContentWithTable(content, tablePrototype, evidence);
    }

    private static void replaceEvidenceContentWithTable(
        Element content,
        Element tablePrototype,
        List<ReportEvidenceItem> evidence
    ) {
        Node parent = content.getParentNode();
        if (parent == null) {
            throw new ReportValidationException(
                "Public evidence section content has no document parent"
            );
        }
        Element table = OoxmlDocxSupport.cloneElement(tablePrototype);
        List<Element> rows = OoxmlDocxSupport.rows(table);
        if (rows.size() < 2) {
            throw new ReportValidationException("Evidence table prototype is incomplete");
        }
        List<Element> headers = OoxmlDocxSupport.cells(rows.getFirst());
        List<String> headerValues = List.of(
            "序号", "舆情事项", "发布时间", "内容摘要", "信息来源"
        );
        if (headers.size() != headerValues.size()) {
            throw new ReportValidationException(
                "Evidence table prototype column count is invalid"
            );
        }
        for (int index = 0; index < headers.size(); index++) {
            OoxmlDocxSupport.setText(headers.get(index), headerValues.get(index));
        }
        OoxmlDocxSupport.repeatTableHeader(rows.getFirst());
        OoxmlDocxSupport.setTableColumnWidths(
            table,
            List.of(475, 2000, 1250, 3400, 1378)
        );

        List<List<String>> values = new ArrayList<>();
        int number = 1;
        for (ReportEvidenceItem item : evidence) {
            values.add(List.of(
                Integer.toString(number++),
                "【%s】%s".formatted(item.riskType().displayName(), required(item.title())),
                evidencePublishedDate(item),
                evidenceExcerpt(item),
                evidenceSource(item)
            ));
        }
        replaceDataRows(table, values);
        List<Element> dataRows = OoxmlDocxSupport.rows(table);
        for (int index = 0; index < evidence.size(); index++) {
            ReportEvidenceItem item = evidence.get(index);
            OoxmlDocxSupport.keepTableRowTogether(dataRows.get(index + 1));
            if (!item.sourceUrl().isBlank()) {
                List<Element> cells = OoxmlDocxSupport.cells(dataRows.get(index + 1));
                appendHyperlinkField(cells.get(4), item.sourceUrl(), "查看原文");
            }
        }
        parent.insertBefore(table, content);
        parent.removeChild(content);
    }

    private static String evidencePublishedDate(ReportEvidenceItem evidence) {
        return evidence.publishedAt() == null
            ? NOT_DISCLOSED
            : LocalDate.ofInstant(evidence.publishedAt(), ZoneOffset.UTC).toString();
    }

    private static String evidenceExcerpt(ReportEvidenceItem evidence) {
        String excerpt = concise(evidence.excerpt(), 180);
        String content = excerpt.isBlank() ? "摘要未返回" : excerpt;
        return evidence.contentTruncated()
            ? content + "（网页内容已按采集上限截断）"
            : content;
    }

    private static String evidenceSource(ReportEvidenceItem evidence) {
        return firstNonBlank(
            evidence.sourceDomain(),
            evidence.sourceProvider(),
            "来源未返回"
        );
    }

    private static void appendHyperlinkField(
        Element cell,
        String sourceUrl,
        String displayText
    ) {
        Element paragraph = OoxmlDocxSupport.firstDescendant(cell, "p");
        if (paragraph == null) {
            throw new ReportValidationException("Evidence source cell has no paragraph");
        }
        Document document = cell.getOwnerDocument();
        Element separatorRun = document.createElementNS(OoxmlDocxSupport.W, "w:r");
        Element separatorText = document.createElementNS(OoxmlDocxSupport.W, "w:t");
        separatorText.setTextContent("；");
        separatorRun.appendChild(separatorText);
        paragraph.appendChild(separatorRun);

        Element field = document.createElementNS(OoxmlDocxSupport.W, "w:fldSimple");
        field.setAttributeNS(
            OoxmlDocxSupport.W,
            "w:instr",
            " HYPERLINK \"%s\" ".formatted(sourceUrl.trim())
        );
        Element linkRun = document.createElementNS(OoxmlDocxSupport.W, "w:r");
        Element properties = document.createElementNS(OoxmlDocxSupport.W, "w:rPr");
        Element color = document.createElementNS(OoxmlDocxSupport.W, "w:color");
        color.setAttributeNS(OoxmlDocxSupport.W, "w:val", "0563C1");
        Element underline = document.createElementNS(OoxmlDocxSupport.W, "w:u");
        underline.setAttributeNS(OoxmlDocxSupport.W, "w:val", "single");
        properties.appendChild(color);
        properties.appendChild(underline);
        linkRun.appendChild(properties);
        Element linkText = document.createElementNS(OoxmlDocxSupport.W, "w:t");
        linkText.setTextContent(displayText);
        linkRun.appendChild(linkText);
        field.appendChild(linkRun);
        paragraph.appendChild(field);
    }

    private static String concise(String value, int maximumLength) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maximumLength
            ? normalized
            : normalized.substring(0, maximumLength) + "…";
    }

    private static String mapping(
        Map<String, String> fieldMapping,
        String field,
        String fallback
    ) {
        String configured = fieldMapping == null ? null : fieldMapping.get(field);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static void replaceCompanyTable(
        Element table,
        CompanyFacts facts,
        Map<String, String> fieldMapping
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(mapping(fieldMapping, "legal_representative", "法定代表人"), facts.legalRepresentative());
        values.put("注册资本", facts.registeredCapital());
        values.put("行业类型", industryType(facts));
        values.put("实缴资本", additional(facts, "paid_in_capital", "real_capital"));
        values.put(mapping(fieldMapping, "registration_status", "经营状态"), facts.registrationStatus());
        values.put("成立日期", facts.establishedDate());
        values.put(mapping(fieldMapping, "unified_credit_code", "统一社会信用代码"), facts.unifiedCreditCode());
        values.put("纳税人识别号", additional(facts, "taxpayer_identification_no", "tax_no"));
        values.put("注册号", facts.registrationNo());
        values.put("组织机构代码", additional(facts, "organization_code", "org_code"));
        values.put("企业类型", facts.companyType());
        values.put("所属行业", industry(facts));
        values.put("核准日期", additional(facts, "approved_date", "approval_date"));
        values.put("登记机关", facts.registrationAuthority());
        values.put("所属地区", additional(facts, "region", "district"));
        values.put("英文名", additional(facts, "english_name"));
        values.put("曾用名", additional(facts, "former_names", "former_name"));
        values.put("参保人数", additional(facts, "insured_count"));
        values.put("人员规模", additional(facts, "staff_size", "employee_scale"));
        values.put("营业期限", additional(facts, "business_term", "operation_period"));
        values.put("企业地址", facts.registeredAddress());
        values.put("经营范围", facts.businessScope());

        for (Element row : OoxmlDocxSupport.rows(table)) {
            List<Element> cells = OoxmlDocxSupport.cells(row);
            for (int index = 0; index + 1 < cells.size(); index += 2) {
                String label = normalized(OoxmlDocxSupport.text(cells.get(index)));
                if (values.containsKey(label)) {
                    OoxmlDocxSupport.setText(cells.get(index + 1), value(values.get(label)));
                }
            }
        }
    }

    private static void replaceBasicRelationSections(
        List<Element> paragraphs,
        Element shareholderTable,
        Element fourColumnPrototype,
        Element fiveColumnPrototype,
        CompanyFacts facts
    ) {
        Element noticePrototype = findNormalized(paragraphs, NO_RELEVANT_INFORMATION);
        Element shareholderHeading = findContaining(paragraphs, "2、股东信息");
        if (noticePrototype == null || shareholderHeading == null) {
            throw new ReportValidationException(
                "Could not locate the V1 shareholder section slots"
            );
        }
        OoxmlDocxSupport.setText(shareholderHeading, "2、股东信息");
        List<Map<String, String>> shareholders = relationRecords(facts, "shareholders");
        if (shareholders.isEmpty()) {
            replaceTableWithNotice(shareholderTable, shareholderHeading, noticePrototype);
        } else {
            configureTable(
                shareholderTable,
                List.of("序号", "股东信息", "持股比例", "认缴出资额（万元）"),
                List.of(600, 3850, 1700, 2553),
                numberedRows(shareholders, relation -> List.of(
                    value(relation.get("name")),
                    value(relation.get("ratio")),
                    amountValue(relation.get("registeredAmount"))
                ))
            );
        }

        replaceWebsiteSection(paragraphs, facts);

        Element personnelHeading = findNumberedHeading(paragraphs, "主要人员");
        Element personnel = personnelHeading == null
            ? null
            : nextNonBlankElement(paragraphs, paragraphs.indexOf(personnelHeading));
        if (personnel == null || isHeading(personnel)) {
            throw new ReportValidationException(
                "Could not locate the V1 key-personnel content slot"
            );
        }
        List<Map<String, String>> personnelRecords = mergePersonnel(
            relationRecords(facts, "keyPersonnel")
        );
        if (personnelRecords.isEmpty()) {
            OoxmlDocxSupport.setText(personnel, NO_RELEVANT_INFORMATION);
        } else {
            replaceContentWithTable(
                personnel,
                fourColumnPrototype,
                List.of("序号", "姓名", "职务", "简介"),
                List.of(600, 1700, 2500, 3903),
                numberedRows(personnelRecords, relation -> List.of(
                    value(relation.get("name")),
                    value(relation.get("position")),
                    value(relation.get("brief"))
                ))
            );
        }

        Element controllerHeading = findNumberedHeading(paragraphs, "疑似实际控制人");
        Element controller = controllerHeading == null
            ? null
            : nextNonBlankElement(paragraphs, paragraphs.indexOf(controllerHeading));
        if (controller == null || isHeading(controller)) {
            throw new ReportValidationException(
                "Could not locate the V1 actual-controller content slot"
            );
        }
        String actualController = additional(
            facts, "actualController", "actual_controller"
        );
        String controllerText = actualController == null
            ? NO_RELEVANT_INFORMATION
            : shareholders.isEmpty()
                ? "疑似实际控制人为%s。暂未监测到完整股权穿透比例信息，不排除信息未对外公示，信息披露滞后等情况。"
                    .formatted(actualController)
                : "疑似实际控制人为%s，股东及持股信息详见本报告股东信息表。"
                    .formatted(actualController);
        OoxmlDocxSupport.setText(controller, controllerText);

        replaceRelationTableSection(
            paragraphs,
            "对外投资控股企业",
            fiveColumnPrototype,
            List.of("序号", "企业名称", "投资金额（万元）", "持股比例", "经营状态"),
            List.of(600, 3000, 1800, 1400, 1903),
            relationRecords(facts, "outboundInvestments"),
            relation -> List.of(
                value(relation.get("name")),
                amountValue(relation.get("registeredAmount")),
                value(relation.get("ratio")),
                value(relation.get("status"))
            )
        );
        replaceRelationTableSection(
            paragraphs,
            "分支机构",
            fourColumnPrototype,
            List.of("序号", "分支机构", "负责人", "经营状态"),
            List.of(600, 4100, 1800, 2203),
            relationRecords(facts, "branches"),
            relation -> List.of(
                value(relation.get("name")),
                value(relation.get("legalRepresentative")),
                value(relation.get("status"))
            )
        );
    }

    private static void replaceWebsiteSection(
        List<Element> paragraphs,
        CompanyFacts facts
    ) {
        Element heading = findNumberedHeading(paragraphs, "官网地址");
        Element content = heading == null
            ? null
            : nextNonBlankElement(paragraphs, paragraphs.indexOf(heading));
        if (content == null || isHeading(content)) {
            throw new ReportValidationException("Could not locate the V1 website content slot");
        }
        Set<String> websites = new LinkedHashSet<>();
        for (Map<String, String> item : relationRecords(facts, "officialWebsites")) {
            String website = item.get("value");
            if (website != null && !website.isBlank()) {
                websites.add(website.trim());
            }
        }
        websites.addAll(jsonStringValues(facts.additionalFields().get("websites")));
        OoxmlDocxSupport.setText(
            content,
            websites.isEmpty() ? NO_RELEVANT_INFORMATION : String.join("；", websites)
        );
    }

    private static void replaceRelationTableSection(
        List<Element> paragraphs,
        String headingText,
        Element prototype,
        List<String> headers,
        List<Integer> widths,
        List<Map<String, String>> records,
        java.util.function.Function<Map<String, String>, List<String>> values
    ) {
        Element heading = findNumberedHeading(paragraphs, headingText);
        Element content = heading == null
            ? null
            : nextNonBlankElement(paragraphs, paragraphs.indexOf(heading));
        if (content == null || isHeading(content)) {
            throw new ReportValidationException(
                "Could not locate the V1 relation section: " + headingText
            );
        }
        if (records.isEmpty()) {
            OoxmlDocxSupport.setText(content, NO_RELEVANT_INFORMATION);
            return;
        }
        replaceContentWithTable(
            content, prototype, headers, widths, numberedRows(records, values)
        );
    }

    private static void replaceContentWithTable(
        Element content,
        Element prototype,
        List<String> headers,
        List<Integer> widths,
        List<List<String>> values
    ) {
        Node parent = content.getParentNode();
        if (parent == null) {
            throw new ReportValidationException("Relation section content has no document parent");
        }
        Element table = OoxmlDocxSupport.cloneElement(prototype);
        configureTable(table, headers, widths, values);
        parent.insertBefore(table, content);
        parent.removeChild(content);
    }

    private static void configureTable(
        Element table,
        List<String> headers,
        List<Integer> widths,
        List<List<String>> values
    ) {
        List<Element> rows = OoxmlDocxSupport.rows(table);
        if (rows.size() < 2) {
            throw new ReportValidationException("Relation table prototype is incomplete");
        }
        List<Element> cells = OoxmlDocxSupport.cells(rows.getFirst());
        if (cells.size() != headers.size() || widths.size() != headers.size()) {
            throw new ReportValidationException("Relation table column count is invalid");
        }
        for (int index = 0; index < cells.size(); index++) {
            OoxmlDocxSupport.setText(cells.get(index), headers.get(index));
        }
        OoxmlDocxSupport.repeatTableHeader(rows.getFirst());
        OoxmlDocxSupport.setTableColumnWidths(table, widths);
        replaceDataRows(table, values);
    }

    private static List<List<String>> numberedRows(
        List<Map<String, String>> records,
        java.util.function.Function<Map<String, String>, List<String>> values
    ) {
        List<List<String>> rows = new ArrayList<>();
        int number = 1;
        for (Map<String, String> record : records) {
            List<String> row = new ArrayList<>();
            row.add(Integer.toString(number++));
            row.addAll(values.apply(record));
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static List<Map<String, String>> mergePersonnel(
        List<Map<String, String>> records
    ) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        for (Map<String, String> record : records) {
            String name = firstNonBlank(record.get("name"), NOT_DISCLOSED);
            Map<String, String> target = merged.computeIfAbsent(
                name, ignored -> new LinkedHashMap<>(record)
            );
            target.put("position", mergeValues(target.get("position"), record.get("position")));
            if (firstNonBlank(target.get("brief")) == null
                && firstNonBlank(record.get("brief")) != null) {
                target.put("brief", record.get("brief"));
            }
        }
        return List.copyOf(merged.values());
    }

    private static String mergeValues(String left, String right) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : new String[] {
            firstNonBlank(left, ""), firstNonBlank(right, "")
        }) {
            if (value == null) {
                continue;
            }
            for (String item : value.split("[,，、;；]")) {
                if (!item.isBlank()) {
                    values.add(item.trim());
                }
            }
        }
        return values.isEmpty() ? null : String.join("、", values);
    }

    private static String amountValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return NOT_DISCLOSED;
        }
        try {
            return new BigDecimal(raw.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return value(raw);
        }
    }

    private static List<Map<String, String>> relationRecords(
        CompanyFacts facts,
        String key
    ) {
        String raw = facts.additionalFields().get(key);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new ReportValidationException(
                "Invalid structured company relation data: " + key
            );
        }
    }

    private static List<String> jsonStringValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return List.of(raw.trim());
        }
    }

    private static void replaceUnsupportedRelatedAppendices(
        List<Element> paragraphs,
        Element legalRepresentativeRelatedTable,
        Element actualControllerRelatedTable
    ) {
        Element noticePrototype = findNormalized(paragraphs, NO_RELEVANT_INFORMATION);
        Element legalHeading = findContaining(paragraphs, "附件：2、法人关联企业");
        Element controllerHeading = findContaining(paragraphs, "附件：3、实控人关联企业");
        if (noticePrototype == null || legalHeading == null || controllerHeading == null) {
            throw new ReportValidationException(
                "Could not locate the V1 related-company appendix slots"
            );
        }
        replaceTableWithNotice(
            legalRepresentativeRelatedTable, legalHeading, noticePrototype
        );
        replaceTableWithNotice(
            actualControllerRelatedTable, controllerHeading, noticePrototype
        );
    }

    private static void replaceTableWithNotice(
        Element table,
        Element heading,
        Element noticePrototype
    ) {
        Node parent = table.getParentNode();
        if (parent == null || heading.getParentNode() != parent) {
            throw new ReportValidationException(
                "V1 table and heading are not in the same document body"
            );
        }
        Element notice = OoxmlDocxSupport.cloneElement(noticePrototype);
        OoxmlDocxSupport.setText(notice, NO_RELEVANT_INFORMATION);
        Node insertionPoint = nextElementSibling(heading);
        parent.insertBefore(notice, insertionPoint);

        Node cursor = nextElementSibling(notice);
        boolean removedTable = false;
        while (cursor != null) {
            Node next = cursor.getNextSibling();
            parent.removeChild(cursor);
            if (cursor == table) {
                removedTable = true;
                break;
            }
            cursor = next;
        }
        if (!removedTable) {
            throw new ReportValidationException(
                "Could not remove the stale V1 table after its heading"
            );
        }
        Node source = nextElementSibling(notice);
        if (source instanceof Element element) {
            String sourceText = normalized(OoxmlDocxSupport.text(element));
            if (sourceText.contains("来源")) {
                parent.removeChild(source);
            }
        }
    }

    private static void replaceChangeSection(
        List<Element> paragraphs,
        Element table,
        DataSnapshot snapshot
    ) {
        List<CompanyChange> changes = snapshot.companyChanges().stream()
            .filter(OoxmlReportTemplateRenderer::hasMeaningfulChangeContent)
            .toList();
        if (changes.isEmpty()) {
            replaceEmptyChangeSection(paragraphs, table);
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        int number = 1;
        for (CompanyChange change : changes) {
            rows.add(List.of(
                Integer.toString(number++),
                value(change.changeItem()),
                value(change.changedAt()),
                value(change.beforeValue()),
                value(change.afterValue())
            ));
        }
        replaceDataRows(table, rows);
    }

    private static boolean hasMeaningfulChangeContent(CompanyChange change) {
        return isMeaningfulChangeValue(change.changeItem())
            || isMeaningfulChangeValue(change.beforeValue())
            || isMeaningfulChangeValue(change.afterValue());
    }

    private static boolean isMeaningfulChangeValue(String value) {
        String normalized = normalized(readableValue(value)).toLowerCase();
        return !normalized.isBlank()
            && !Set.of(
                "/", "-", "--", "—", "无", "暂无", "未公示", "无法记录",
                "无公开记录", "null", "n/a"
            ).contains(normalized);
    }

    private static void replaceEmptyChangeSection(
        List<Element> paragraphs,
        Element table
    ) {
        Element caption = findNormalized(paragraphs, "表3 工商变更信息");
        if (caption == null) {
            caption = findContaining(paragraphs, "表3 工商变更信息");
        }
        Element noticePrototype = findNormalized(paragraphs, NO_RELEVANT_INFORMATION);
        if (caption == null) {
            throw new ReportValidationException(
                "Could not locate the V1 empty company-change section slots"
            );
        }

        Node parent = table.getParentNode();
        Node source = nextElementSibling(table);
        if (noticePrototype == null) {
            noticePrototype = source instanceof Element element
                && "p".equals(element.getLocalName())
                ? element
                : caption;
        }
        Element notice = OoxmlDocxSupport.cloneElement(noticePrototype);
        OoxmlDocxSupport.setText(notice, NO_RELEVANT_INFORMATION);
        parent.insertBefore(notice, caption);
        parent.removeChild(caption);

        parent.removeChild(table);
        if (source instanceof Element element) {
            String sourceText = normalized(OoxmlDocxSupport.text(element));
            if (sourceText.contains("来源") && sourceText.contains("工商信息")) {
                parent.removeChild(source);
            }
        }
    }

    private static Node nextElementSibling(Node node) {
        for (Node sibling = node.getNextSibling(); sibling != null;
             sibling = sibling.getNextSibling()) {
            if (sibling instanceof Element) {
                return sibling;
            }
        }
        return null;
    }

    private static void replaceStructuredRiskSections(
        List<Element> paragraphs,
        DataSnapshot snapshot,
        Element tablePrototype
    ) {
        Map<RiskType, List<RiskEvent>> events = snapshot.riskEvents().stream()
            .collect(Collectors.groupingBy(
                event -> RiskType.fromCanonicalName(event.eventType()),
                LinkedHashMap::new,
                Collectors.toList()
            ));
        String asOf = dataAsOf(snapshot).format(CN_DATE);
        for (Map.Entry<String, RiskType> section : RISK_SECTIONS.entrySet()) {
            Element heading = findNumberedHeading(paragraphs, section.getKey());
            if (heading == null) {
                continue;
            }
            Element content = nextNonBlankElement(paragraphs, paragraphs.indexOf(heading));
            if (content == null || isHeading(content)) {
                continue;
            }
            List<RiskEvent> matches = events.getOrDefault(section.getValue(), List.of());
            if (matches.isEmpty()) {
                OoxmlDocxSupport.setText(content, "    " + NO_RELEVANT_INFORMATION);
                continue;
            }
            replaceRiskContentWithTable(content, tablePrototype, section.getValue(), matches);
        }
    }

    private static void replaceRiskContentWithTable(
        Element content,
        Element tablePrototype,
        RiskType riskType,
        List<RiskEvent> events
    ) {
        Node parent = content.getParentNode();
        if (parent == null) {
            throw new ReportValidationException("Risk section content has no document parent");
        }
        Element table = OoxmlDocxSupport.cloneElement(tablePrototype);
        List<Element> rows = OoxmlDocxSupport.rows(table);
        if (rows.size() < 2) {
            throw new ReportValidationException("Risk table prototype is incomplete");
        }
        List<Element> headers = OoxmlDocxSupport.cells(rows.getFirst());
        List<String> headerValues = List.of("序号", "风险事项", "日期", "主要内容", "信息来源");
        if (headers.size() != headerValues.size()) {
            throw new ReportValidationException("Risk table prototype column count is invalid");
        }
        for (int index = 0; index < headers.size(); index++) {
            OoxmlDocxSupport.setText(headers.get(index), headerValues.get(index));
        }
        OoxmlDocxSupport.repeatTableHeader(rows.getFirst());
        OoxmlDocxSupport.setTableColumnWidths(
            table,
            List.of(475, 1600, 1250, 3400, 1778)
        );

        List<List<String>> values = new ArrayList<>();
        int number = 1;
        for (RiskEvent event : events) {
            values.add(List.of(
                Integer.toString(number++),
                firstNonBlank(event.title(), riskType.displayName()),
                eventDate(event),
                eventDetail(event),
                eventSource(riskType)
            ));
        }
        replaceDataRows(table, values);
        parent.insertBefore(table, content);
        parent.removeChild(content);
    }

    private static String eventDate(RiskEvent event) {
        if (isSourceUpdateFallback(event)) {
            return NOT_DISCLOSED;
        }
        String value = firstNonBlank(event.occurredAt(), NOT_DISCLOSED);
        int separator = value.indexOf('T');
        return separator > 0 ? value.substring(0, separator) : value;
    }

    private static boolean isSourceUpdateFallback(RiskEvent event) {
        if (event.dataAsOf() == null || event.occurredAt() == null) {
            return false;
        }
        try {
            return parseEventInstant(event.occurredAt()).equals(event.dataAsOf());
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static Instant parseEventInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    private static String eventDetail(RiskEvent event) {
        List<String> details = new ArrayList<>();
        addDetail(details, "文号", event.rawFields().get("documentNo"));
        addDetail(details, "机关", event.rawFields().get("authority"));
        addDetail(details, "状态", event.rawFields().get("status"));
        String summary = firstNonBlank(event.summary(), "");
        if (!summary.isBlank() && !normalized(summary).equals(normalized(event.title()))) {
            details.add(summary);
        }
        return details.isEmpty() ? firstNonBlank(event.summary(), event.title(), NOT_DISCLOSED)
            : String.join("；", details);
    }

    private static void addDetail(List<String> details, String label, String value) {
        if (value != null && !value.isBlank() && !NOT_DISCLOSED.equals(value.trim())) {
            details.add(label + "：" + value.trim());
        }
    }

    private static String eventSource(RiskType type) {
        return switch (type) {
            case ADMINISTRATIVE_PENALTY, ENVIRONMENTAL_PENALTY,
                 SERIOUS_ILLEGAL, BUSINESS_ABNORMAL, TAX_ILLEGAL -> "行政监管公开信息";
            case JUDGMENT_DEBTOR, JUDICIAL_DOCUMENT, DISHONEST,
                 LIMIT_CONSUMPTION, BANKRUPTCY, JUDICIAL_AUCTION -> "司法公开信息";
            case EQUITY_PLEDGE, EQUITY_FREEZE, STOCK_PLEDGE -> "股权登记公开信息";
            default -> "企业结构化数据库";
        };
    }

    private static void replaceDataRows(Element table, List<List<String>> values) {
        List<Element> rows = OoxmlDocxSupport.rows(table);
        if (rows.size() < 2) {
            throw new ReportValidationException("Dynamic table has no source data row");
        }
        Element prototype = OoxmlDocxSupport.cloneElement(rows.get(1));
        for (int index = rows.size() - 1; index >= 1; index--) {
            table.removeChild(rows.get(index));
        }
        for (List<String> rowValues : values) {
            Element row = OoxmlDocxSupport.cloneElement(prototype);
            List<Element> cells = OoxmlDocxSupport.cells(row);
            if (cells.size() != rowValues.size()) {
                throw new ReportValidationException(
                    "Dynamic table source row does not match expected column count"
                );
            }
            for (int index = 0; index < cells.size(); index++) {
                OoxmlDocxSupport.setText(cells.get(index), rowValues.get(index));
            }
            table.appendChild(row);
        }
    }

    private static byte[] updateFields(byte[] settingsXml) {
        if (settingsXml == null) {
            throw new ReportValidationException("V1 template has no word/settings.xml");
        }
        Document settings = OoxmlDocxSupport.parse(settingsXml);
        NodeList existing = settings.getElementsByTagNameNS(OoxmlDocxSupport.W, "updateFields");
        Element update;
        if (existing.getLength() > 0) {
            update = (Element) existing.item(0);
        } else {
            update = settings.createElementNS(OoxmlDocxSupport.W, "w:updateFields");
            settings.getDocumentElement().appendChild(update);
        }
        update.setAttributeNS(OoxmlDocxSupport.W, "w:val", "true");
        return OoxmlDocxSupport.serialize(settings);
    }

    private static void requireCoreText(
        byte[] output,
        CompanyFacts facts,
        RiskScoreSnapshot score,
        LocalDate date
    ) {
        Document document = OoxmlDocxSupport.parse(
            OoxmlDocxSupport.unzip(output).get("word/document.xml")
        );
        String allText = document.getDocumentElement().getTextContent();
        List<String> required = List.of(
            facts.canonicalName(),
            facts.unifiedCreditCode(),
            decimal(score.originalScore()),
            decimal(score.manualScore()),
            date.format(CN_DATE)
        );
        for (String value : required) {
            if (value == null || value.isBlank() || !allText.contains(value)) {
                throw new ReportValidationException(
                    "Generated DOCX is missing a required core field"
                );
            }
        }
    }

    private static String scoreText(RiskScoreSnapshot score) {
        String original = "%s（%s）".formatted(
            decimal(score.originalScore()),
            level(score.originalRiskLevel())
        );
        String manual = "%s（%s）".formatted(
            decimal(score.manualScore()),
            level(score.manualRiskLevel())
        );
        return score.originalScore().compareTo(score.manualScore()) == 0
            ? " " + original
            : " 原始分 " + original + "；人工分 " + manual;
    }

    private static String riskTags(ReportGenerationData data) {
        Set<String> labels = new LinkedHashSet<>();
        for (RiskRuleHit hit : data.riskScore().ruleHits()) {
            if (isScoringHit(hit)
                && hit.riskType() != null
                && hit.riskType() != RiskType.OTHER) {
                labels.add(hit.riskType().displayName());
            }
        }
        return labels.isEmpty()
            ? "暂无（历史风险记录详见正文）"
            : String.join("、", labels);
    }

    private static int scoringHitCount(RiskScoreSnapshot score) {
        return (int) score.ruleHits().stream().filter(OoxmlReportTemplateRenderer::isScoringHit).count();
    }

    private static boolean isScoringHit(RiskRuleHit hit) {
        return hit.score() != null
            && hit.score().signum() > 0
            && Set.of("BASE_SCORE", "RISK_LABEL_SCORE", "EVENT_FLOOR", "RULE_SCORE")
                .contains(hit.scoreRole());
    }

    private static String level(RiskLevel level) {
        return switch (level) {
            case LOW -> "低风险";
            case MEDIUM_LOW -> "中低风险";
            case MEDIUM -> "中风险";
            case MEDIUM_HIGH -> "中高风险";
            case HIGH -> "高风险";
        };
    }

    private static LocalDate dataAsOf(DataSnapshot snapshot) {
        Instant value = snapshot.companyFacts().dataAsOf();
        return value == null
            ? LocalDate.ofInstant(snapshot.frozenAt(), ZoneOffset.UTC)
            : LocalDate.ofInstant(value, ZoneOffset.UTC);
    }

    private static void setNextParagraph(
        List<Element> paragraphs,
        String label,
        String value
    ) {
        int index = findExactIndex(paragraphs, label);
        Element next = nextNonBlankElement(paragraphs, index);
        if (index < 0 || next == null) {
            throw new ReportValidationException("Could not locate template slot: " + label);
        }
        OoxmlDocxSupport.setText(next, value);
    }

    private static Element findNumberedHeading(
        List<Element> paragraphs,
        String heading
    ) {
        for (Element paragraph : paragraphs) {
            String text = normalized(OoxmlDocxSupport.text(paragraph));
            if (text.matches("\\d+[、.]\\s*" + java.util.regex.Pattern.quote(heading) + "\\s*")) {
                return paragraph;
            }
        }
        return null;
    }

    private static Element findExact(List<Element> paragraphs, String value) {
        int index = findExactIndex(paragraphs, value);
        return index < 0 ? null : paragraphs.get(index);
    }

    private static Element findNormalized(List<Element> paragraphs, String value) {
        String target = normalized(value);
        for (Element paragraph : paragraphs) {
            if (normalized(OoxmlDocxSupport.text(paragraph)).equals(target)) {
                return paragraph;
            }
        }
        return null;
    }

    private static Element findContaining(List<Element> paragraphs, String value) {
        String target = normalized(value);
        for (Element paragraph : paragraphs) {
            if (normalized(OoxmlDocxSupport.text(paragraph)).contains(target)) {
                return paragraph;
            }
        }
        return null;
    }

    private static int findExactIndex(List<Element> paragraphs, String value) {
        if (value == null) {
            return -1;
        }
        String target = normalized(value);
        for (int index = 0; index < paragraphs.size(); index++) {
            if (normalized(OoxmlDocxSupport.text(paragraphs.get(index))).equals(target)) {
                return index;
            }
        }
        return -1;
    }

    private static int findContainingIndex(List<Element> paragraphs, String value) {
        for (int index = 0; index < paragraphs.size(); index++) {
            if (OoxmlDocxSupport.text(paragraphs.get(index)).contains(value)) {
                return index;
            }
        }
        return -1;
    }

    private static Element firstMatching(List<Element> paragraphs, String regex) {
        for (Element paragraph : paragraphs) {
            if (OoxmlDocxSupport.text(paragraph).matches(regex)) {
                return paragraph;
            }
        }
        return null;
    }

    private static Element previousNonBlank(List<Element> paragraphs, int before) {
        for (int index = before - 1; index >= 0; index--) {
            if (!normalized(OoxmlDocxSupport.text(paragraphs.get(index))).isBlank()) {
                return paragraphs.get(index);
            }
        }
        return null;
    }

    private static Element nextNonBlankElement(List<Element> paragraphs, int after) {
        if (after < 0) {
            return null;
        }
        for (int index = after + 1; index < paragraphs.size(); index++) {
            if (!normalized(OoxmlDocxSupport.text(paragraphs.get(index))).isBlank()) {
                return paragraphs.get(index);
            }
        }
        return null;
    }

    private static List<Element> nextNonBlank(
        List<Element> paragraphs,
        int after,
        int count
    ) {
        List<Element> result = new ArrayList<>();
        for (int index = after + 1; index < paragraphs.size() && result.size() < count; index++) {
            if (!normalized(OoxmlDocxSupport.text(paragraphs.get(index))).isBlank()) {
                result.add(paragraphs.get(index));
            }
        }
        return result;
    }

    private static boolean isHeading(Element paragraph) {
        String style = OoxmlDocxSupport.paragraphStyle(paragraph);
        return "1".equals(style) || "2".equals(style)
            || style.toLowerCase().contains("heading");
    }

    private static String additional(CompanyFacts facts, String... keys) {
        for (String key : keys) {
            String value = facts.additionalFields().get(key);
            if (value != null && !value.isBlank()) {
                return readableValue(value);
            }
        }
        return null;
    }

    private static String value(String value) {
        String readable = readableValue(value);
        return readable == null || readable.isBlank() ? NOT_DISCLOSED : readable;
    }

    private static String industryType(CompanyFacts facts) {
        String raw = firstNonBlank(
            facts.additionalFields().get("industry_type"),
            facts.industry()
        );
        return firstNonBlank(
            jsonField(raw, "国标行业小类", "行业小类", "国标行业中类", "行业中类"),
            plainValue(raw)
        );
    }

    private static String industry(CompanyFacts facts) {
        String raw = facts.industry();
        return firstNonBlank(
            jsonField(raw, "国标行业大类", "行业大类", "国标行业门类", "行业门类"),
            plainValue(raw)
        );
    }

    private static String jsonField(String raw, String... preferredKeys) {
        if (raw == null || !raw.stripLeading().startsWith("{")) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = JSON_STRING_FIELD.matcher(raw);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2));
        }
        for (String key : preferredKeys) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fields.values().stream()
            .filter(item -> item != null && !item.isBlank())
            .findFirst()
            .map(String::trim)
            .orElse(null);
    }

    private static String plainValue(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[")
            ? null
            : readableValue(trimmed);
    }

    private static String readableValue(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isBlank()
            || "null".equalsIgnoreCase(trimmed)
            || "[]".equals(trimmed)
            || "{}".equals(trimmed)
            || "\"\"".equals(trimmed)) {
            return null;
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            List<String> items = new ArrayList<>();
            Matcher matcher = JSON_STRING_ITEM.matcher(trimmed);
            while (matcher.find()) {
                String item = matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim();
                if (!item.isBlank()) {
                    items.add(item);
                }
            }
            return items.isEmpty() ? null : String.join("、", items);
        }
        return trimmed;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new ReportValidationException("Required report field is missing");
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
