package com.atlas.enterprise.report.docx;

import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.PreviousReportType;
import com.atlas.enterprise.report.port.PreviousReportParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
public class OoxmlPreviousReportParser implements PreviousReportParser {
    private static final Pattern DATE = Pattern.compile(
        "(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日"
    );
    private static final Pattern SCORE = Pattern.compile(
        "(?:原始分\\s*)?(\\d+(?:\\.\\d+)?)\\s*[（(]?([\\u4e00-\\u9fa5]+风险)"
    );
    private static final Pattern NARRATIVE_RISK_LEVEL = Pattern.compile(
        "(?:综合判定|综合判断|综合评定)[^。；]{0,120}?为([\\u4e00-\\u9fa5]+风险)"
    );
    private static final List<String> STANDARD_HEADINGS = List.of(
        "综述", "舆情投诉风险", "基本情况", "经营风险",
        "涉法涉诉", "关联公司风险", "群体性信息"
    );
    private static final List<String> NEW_REGISTRATION_HEADINGS = List.of(
        "一、企业风险综述",
        "二、新注册企业背景及风险排查情况",
        "三、投资方背景及风险排查情况"
    );

    @Override
    public PreviousReport parse(byte[] docx) {
        Map<String, byte[]> parts = OoxmlDocxSupport.unzip(docx);
        Document document = OoxmlDocxSupport.parse(parts.get("word/document.xml"));
        List<Element> paragraphs = OoxmlDocxSupport.bodyParagraphs(document);
        List<String> texts = paragraphs.stream().map(OoxmlDocxSupport::text).toList();
        List<Element> tables = OoxmlDocxSupport.bodyTables(document);

        PreviousReportType reportType = reportType(texts);
        String companyName = companyName(texts);
        LocalDate reportDate = firstDate(texts);
        LocalDate dataAsOf = dataAsOf(texts);
        Map<String, String> companyFields = companyFields(tables);
        Score score = score(texts);
        List<String> conclusions = conclusions(texts, reportType);
        Map<String, List<String>> sections = sections(texts, reportType);

        int evidence = 0;
        evidence += companyName == null ? 0 : 1;
        evidence += reportDate == null ? 0 : 1;
        evidence += dataAsOf == null ? 0 : 1;
        evidence += companyFields.containsKey("统一社会信用代码") ? 1 : 0;
        evidence += companyFields.containsKey("法定代表人")
            || companyFields.containsKey("负责人") ? 1 : 0;
        evidence += score.value == null ? 0 : 1;
        evidence += conclusions.isEmpty() ? 0 : 1;
        double confidence = evidence / 7.0d;
        boolean recognized = reportType == PreviousReportType.STANDARD_RISK_REPORT
            && texts.stream().anyMatch(value -> value.contains("风险监测分析报告"))
            && STANDARD_HEADINGS.stream().allMatch(heading ->
                texts.stream().anyMatch(value -> normalized(value).equals(heading))
            )
            && tables.size() >= 3;
        boolean supportedForUpdate = recognized && confidence >= 0.80d;
        List<String> warnings = warnings(
            reportType,
            companyName,
            reportDate,
            dataAsOf,
            companyFields,
            score,
            conclusions,
            supportedForUpdate
        );

        return new PreviousReport(
            recognized,
            confidence,
            reportDate,
            dataAsOf,
            companyName,
            companyFields,
            score.value,
            score.manual,
            score.level,
            conclusions,
            sections,
            List.of(
                "目录内容控件：保留",
                "综述浮动风险图：保留",
                "页眉页脚和域：保留"
            ),
            reportType,
            supportedForUpdate,
            warnings,
            OoxmlDocxSupport.sha256(docx)
        );
    }

    private static PreviousReportType reportType(List<String> texts) {
        if (containsExact(texts, "一、企业风险综述")
            && containsExact(texts, "二、新注册企业背景及风险排查情况")) {
            return PreviousReportType.NEW_REGISTRATION_BACKGROUND_REVIEW;
        }
        if (containsExact(texts, "综述")
            && containsExact(texts, "舆情投诉风险")
            && containsExact(texts, "基本情况")) {
            return PreviousReportType.STANDARD_RISK_REPORT;
        }
        return PreviousReportType.UNKNOWN;
    }

    private static String companyName(List<String> texts) {
        int title = indexContaining(texts, "风险监测分析报告");
        for (int index = title - 1; index >= 0; index--) {
            String value = normalized(texts.get(index));
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static LocalDate firstDate(List<String> texts) {
        for (String text : texts) {
            LocalDate date = parseDate(text);
            if (date != null) {
                return date;
            }
        }
        return null;
    }

    private static LocalDate dataAsOf(List<String> texts) {
        for (String text : texts) {
            if (text.contains("截至")) {
                LocalDate date = parseDate(text);
                if (date != null) {
                    return date;
                }
            }
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return LocalDate.of(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        );
    }

    private static Map<String, String> companyFields(List<Element> tables) {
        if (tables.isEmpty()) {
            return Map.of();
        }
        Map<String, String> fallback = Map.of();
        for (Element table : tables) {
            Map<String, String> candidate = tableFields(table);
            if (fallback.isEmpty() && !candidate.isEmpty()) fallback = candidate;
            if (candidate.containsKey("统一社会信用代码")) return candidate;
        }
        return fallback;
    }

    private static Map<String, String> tableFields(Element table) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element row : OoxmlDocxSupport.rows(table)) {
            List<Element> cells = OoxmlDocxSupport.cells(row);
            for (int index = 0; index + 1 < cells.size(); index += 2) {
                String label = normalized(OoxmlDocxSupport.text(cells.get(index)));
                String value = normalized(OoxmlDocxSupport.text(cells.get(index + 1)));
                if (!label.isBlank()) {
                    result.put(label, value);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Score score(List<String> texts) {
        for (String text : texts) {
            Matcher matcher = SCORE.matcher(text);
            if (matcher.find()) {
                BigDecimal value = new BigDecimal(matcher.group(1));
                BigDecimal manual = null;
                Matcher second = SCORE.matcher(text.substring(matcher.end()));
                if (second.find()) {
                    manual = new BigDecimal(second.group(1));
                }
                return new Score(value, manual, matcher.group(2));
            }
        }
        for (String text : texts) {
            Matcher matcher = NARRATIVE_RISK_LEVEL.matcher(text);
            if (matcher.find()) {
                return new Score(null, null, matcher.group(1));
            }
        }
        return new Score(null, null, null);
    }

    private static List<String> conclusions(
        List<String> texts,
        PreviousReportType reportType
    ) {
        if (reportType == PreviousReportType.NEW_REGISTRATION_BACKGROUND_REVIEW) {
            return between(
                texts,
                "一、企业风险综述",
                "二、新注册企业背景及风险排查情况"
            );
        }
        int start = indexExact(texts, "总结：");
        int end = indexExact(texts, "舆情投诉风险");
        return between(texts, start, end);
    }

    private static List<String> between(
        List<String> texts,
        String startHeading,
        String endHeading
    ) {
        return between(
            texts,
            indexExact(texts, startHeading),
            indexExact(texts, endHeading)
        );
    }

    private static List<String> between(
        List<String> texts,
        int start,
        int end
    ) {
        if (start < 0 || end <= start) return List.of();
        List<String> result = new ArrayList<>();
        for (int index = start + 1; index < end; index++) {
            String value = normalized(texts.get(index));
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private static Map<String, List<String>> sections(
        List<String> texts,
        PreviousReportType reportType
    ) {
        List<String> headings = reportType
            == PreviousReportType.NEW_REGISTRATION_BACKGROUND_REVIEW
            ? NEW_REGISTRATION_HEADINGS
            : STANDARD_HEADINGS;
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int headingIndex = 0; headingIndex < headings.size(); headingIndex++) {
            String heading = headings.get(headingIndex);
            int start = indexExact(texts, heading);
            if (start < 0) {
                continue;
            }
            int end = texts.size();
            for (int next = headingIndex + 1; next < headings.size(); next++) {
                int candidate = indexExact(texts, headings.get(next));
                if (candidate > start) {
                    end = candidate;
                    break;
                }
            }
            List<String> content = new ArrayList<>();
            for (int index = start + 1; index < end; index++) {
                String value = normalized(texts.get(index));
                if (!value.isBlank()) {
                    content.add(value);
                }
            }
            result.put(heading, List.copyOf(content));
        }
        return result;
    }

    private static List<String> warnings(
        PreviousReportType reportType,
        String companyName,
        LocalDate reportDate,
        LocalDate dataAsOf,
        Map<String, String> companyFields,
        Score score,
        List<String> conclusions,
        boolean supportedForUpdate
    ) {
        List<String> result = new ArrayList<>();
        if (reportType == PreviousReportType.NEW_REGISTRATION_BACKGROUND_REVIEW) {
            result.add("REPORT_TYPE_UNSUPPORTED: 当前V1不处理新注册企业背景及风险排查报告");
        } else if (reportType == PreviousReportType.UNKNOWN) {
            result.add("REPORT_TYPE_UNKNOWN: 未识别到受支持的旧报告结构");
        }
        if (companyName == null) {
            result.add("COMPANY_NAME_MISSING: 未识别到企业名称");
        }
        if (reportDate == null) {
            result.add("REPORT_DATE_MISSING: 未识别到报告日期");
        }
        if (dataAsOf == null) {
            result.add("DATA_AS_OF_MISSING: 未识别到数据截止日期");
        }
        if (!companyFields.containsKey("统一社会信用代码")) {
            result.add("CREDIT_CODE_MISSING: 未识别到统一社会信用代码");
        }
        if (score.value == null) {
            result.add("ORIGINAL_SCORE_MISSING: 未识别到旧报告数值评分");
        }
        if (score.level == null) {
            result.add("RISK_LEVEL_MISSING: 未识别到旧报告风险等级");
        }
        if (conclusions.isEmpty()) {
            result.add("CONCLUSION_MISSING: 未识别到旧报告风险结论");
        }
        if (reportType == PreviousReportType.STANDARD_RISK_REPORT
            && !supportedForUpdate) {
            result.add("CONFIDENCE_TOO_LOW: 标准报告解析置信度低于0.80");
        }
        return List.copyOf(result);
    }

    private static int indexContaining(List<String> texts, String target) {
        for (int index = 0; index < texts.size(); index++) {
            if (texts.get(index).contains(target)) {
                return index;
            }
        }
        return -1;
    }

    private static int indexExact(List<String> texts, String target) {
        for (int index = 0; index < texts.size(); index++) {
            if (normalized(texts.get(index)).equals(target)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean containsExact(List<String> texts, String target) {
        return indexExact(texts, target) >= 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private record Score(BigDecimal value, BigDecimal manual, String level) {
    }
}
