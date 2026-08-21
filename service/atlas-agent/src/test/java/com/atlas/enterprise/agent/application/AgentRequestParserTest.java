package com.atlas.enterprise.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AgentRequestParserTest {
    private final AgentRequestParser parser = new AgentRequestParser();

    @Test
    void extractsAFullCompanyNameFromRiskTask() {
        ParsedAgentRequest result = parser.parse(
            "调查北京童程童慧科技有限公司近一年的经营状况，重点核实失联、拖欠工资和门店关闭。",
            false
        );

        assertEquals(AgentIntent.CREATE_RISK_REPORT_TASK, result.intent());
        assertEquals("北京童程童慧科技有限公司", result.companyQuery());
    }

    @Test
    void acceptsUnifiedCreditCodeAsCompanyAnchor() {
        ParsedAgentRequest result = parser.parse(
            "更新 91110101JSON000001 的风险报告",
            false
        );

        assertEquals(AgentIntent.CREATE_RISK_REPORT_TASK, result.intent());
        assertEquals("91110101JSON000001", result.companyQuery());
    }

    @Test
    void recognizesTaskNumberStatusQuestion() {
        ParsedAgentRequest result = parser.parse(
            "任务 AT-20260730-ABCDEF12 进度到哪了？",
            false
        );

        assertEquals(AgentIntent.QUERY_TASK_STATUS, result.intent());
        assertEquals("AT-20260730-ABCDEF12", result.taskReference());
    }

    @Test
    void recognizesTaskIdStatusQuestion() {
        ParsedAgentRequest result = parser.parse(
            "查询任务 00000000-0000-0000-0000-000000000123 的状态",
            false
        );

        assertEquals(AgentIntent.QUERY_TASK_STATUS, result.intent());
        assertEquals(
            "00000000-0000-0000-0000-000000000123",
            result.taskReference()
        );
    }

    @Test
    void rejects招商WorkButDoesNotReject招商银行CompanyName() {
        ParsedAgentRequest unsupported = parser.parse(
            "分析北京示例有限公司的招商线索",
            false
        );
        ParsedAgentRequest bank = parser.parse(
            "更新招商银行股份有限公司的风险报告",
            false
        );

        assertEquals(AgentIntent.UNSUPPORTED_SCOPE, unsupported.intent());
        assertEquals(AgentIntent.CREATE_RISK_REPORT_TASK, bank.intent());
        assertEquals("招商银行股份有限公司", bank.companyQuery());
    }

    @Test
    void asksForMoreInputWhenNoCompanyOrTaskCanBeFound() {
        ParsedAgentRequest result = parser.parse("帮我看一下", false);

        assertEquals(AgentIntent.UNKNOWN, result.intent());
        assertNull(result.companyQuery());
        assertNull(result.taskReference());
    }
}
