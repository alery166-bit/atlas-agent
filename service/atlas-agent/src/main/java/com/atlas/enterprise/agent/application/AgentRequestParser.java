package com.atlas.enterprise.agent.application;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AgentRequestParser {
    private static final Pattern TASK_NO = Pattern.compile(
        "\\bAT-\\d{8}-[A-F0-9]{8}\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TASK_ID = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );
    private static final Pattern CREDIT_CODE = Pattern.compile(
        "\\b[0-9A-Z]{18}\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMPANY = Pattern.compile(
        "([\\p{IsHan}A-Za-z0-9（）()·\\-]{2,120}?"
            + "(?:集团股份有限公司|集团有限公司|股份有限公司|有限责任公司|"
            + "有限公司|有限合伙企业|普通合伙企业|合伙企业|个人独资企业|"
            + "律师事务所|农民专业合作社|特殊普通合伙|分公司))"
    );
    private static final Pattern LEADING_COMMAND = Pattern.compile(
        "^(?:(?:请帮我|麻烦帮我|我想要|我想|我们要|需要|帮我|给我|请|麻烦|"
            + "调查一下|查询一下|核查一下|排查一下|更新一下|分析一下|"
            + "调查|查询|核查|排查|更新|分析|评估|生成|对|一下))+"
    );

    public ParsedAgentRequest parse(String message, boolean hasTaskId) {
        String normalized = message.trim();
        String taskReference = find(TASK_NO, normalized);
        if (taskReference == null) {
            taskReference = find(TASK_ID, normalized);
        }
        String company = extractCompany(normalized);

        if (isUnsupportedScope(normalized)) {
            return new ParsedAgentRequest(
                AgentIntent.UNSUPPORTED_SCOPE,
                company,
                taskReference
            );
        }
        if (hasTaskId || taskReference != null || isStatusQuestion(normalized)) {
            return new ParsedAgentRequest(
                AgentIntent.QUERY_TASK_STATUS,
                company,
                taskReference
            );
        }
        if (company != null && isRiskTask(normalized)) {
            return new ParsedAgentRequest(
                AgentIntent.CREATE_RISK_REPORT_TASK,
                company,
                null
            );
        }
        return new ParsedAgentRequest(
            AgentIntent.UNKNOWN,
            company,
            taskReference
        );
    }

    private static String extractCompany(String message) {
        String creditCode = find(CREDIT_CODE, message.toUpperCase(Locale.ROOT));
        if (creditCode != null) {
            return creditCode;
        }
        Matcher matcher = COMPANY.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1);
        String cleaned = LEADING_COMMAND.matcher(candidate)
            .replaceFirst("")
            .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static boolean isUnsupportedScope(String message) {
        return containsAny(
            message,
            "招商线索",
            "招商价值",
            "招商评估",
            "招商报告",
            "招商机会"
        );
    }

    private static boolean isStatusQuestion(String message) {
        return containsAny(
            message,
            "进度",
            "状态",
            "到哪",
            "怎么样了",
            "完成了吗",
            "报告好了吗",
            "查一下任务"
        );
    }

    private static boolean isRiskTask(String message) {
        return containsAny(
            message,
            "风险",
            "报告",
            "调查",
            "核查",
            "排查",
            "经营状况",
            "经营情况",
            "失联",
            "欠薪",
            "拖欠工资",
            "闭店",
            "门店关闭"
        );
    }

    private static String find(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
