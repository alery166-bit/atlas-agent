package com.atlas.enterprise.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.atlas.enterprise.agent.port.AgentIntentModel;
import com.atlas.enterprise.agent.port.AgentIntentPrediction;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRequestInterpreterTest {
    private final AgentRequestParser fallbackParser = new AgentRequestParser();

    @Test
    void acceptsAValidSchemaConformantModelPrediction() {
        AgentRequestInterpreter interpreter = interpreterReturning(
            new AgentIntentPrediction(
                AgentRequestInterpreter.SCHEMA_VERSION,
                "CREATE_RISK_REPORT_TASK",
                "北京模型识别科技有限公司",
                null,
                0.93D
            )
        );

        ParsedAgentRequest result = interpreter.interpret(
            "帮我调查这家企业最近的经营风险",
            false
        );

        assertEquals(
            AgentIntent.CREATE_RISK_REPORT_TASK,
            result.intent()
        );
        assertEquals(
            "北京模型识别科技有限公司",
            result.companyQuery()
        );
    }

    @Test
    void fallsBackWhenPredictionDoesNotMatchSchema() {
        AgentRequestInterpreter interpreter = interpreterReturning(
            new AgentIntentPrediction(
                "agent-intent.v2",
                "CREATE_RISK_REPORT_TASK",
                "错误模型结果有限公司",
                null,
                0.99D
            )
        );

        ParsedAgentRequest result = interpreter.interpret(
            "更新北京童程童慧科技有限公司的风险报告",
            false
        );

        assertEquals(
            AgentIntent.CREATE_RISK_REPORT_TASK,
            result.intent()
        );
        assertEquals(
            "北京童程童慧科技有限公司",
            result.companyQuery()
        );
    }

    @Test
    void fallsBackWhenModelFailsOrConfidenceIsLow() {
        AgentIntentModel failingModel = request -> {
            throw new IllegalStateException("model unavailable");
        };
        AgentRequestInterpreter failingInterpreter =
            new AgentRequestInterpreter(
                fallbackParser,
                List.of(failingModel)
            );
        AgentRequestInterpreter lowConfidenceInterpreter =
            interpreterReturning(new AgentIntentPrediction(
                AgentRequestInterpreter.SCHEMA_VERSION,
                "CREATE_RISK_REPORT_TASK",
                "错误模型结果有限公司",
                null,
                0.42D
            ));

        ParsedAgentRequest failed = failingInterpreter.interpret(
            "帮我看一下",
            false
        );
        ParsedAgentRequest lowConfidence = lowConfidenceInterpreter.interpret(
            "更新北京童程童慧科技有限公司的风险报告",
            false
        );

        assertEquals(AgentIntent.UNKNOWN, failed.intent());
        assertNull(failed.companyQuery());
        assertEquals(
            "北京童程童慧科技有限公司",
            lowConfidence.companyQuery()
        );
    }

    @Test
    void deterministicScopeAndTaskGuardsBypassTheModel() {
        AgentIntentModel forbiddenModel = request -> {
            throw new AssertionError("guarded request reached the model");
        };
        AgentRequestInterpreter interpreter = new AgentRequestInterpreter(
            fallbackParser,
            List.of(forbiddenModel)
        );

        ParsedAgentRequest unsupported = interpreter.interpret(
            "分析北京示例有限公司的招商线索",
            false
        );
        ParsedAgentRequest taskStatus = interpreter.interpret(
            "任务 AT-20260730-ABCDEF12 进度到哪了？",
            false
        );
        ParsedAgentRequest contextualTask = interpreter.interpret(
            "继续",
            true
        );

        assertEquals(
            AgentIntent.UNSUPPORTED_SCOPE,
            unsupported.intent()
        );
        assertEquals(
            AgentIntent.QUERY_TASK_STATUS,
            taskStatus.intent()
        );
        assertEquals(
            AgentIntent.QUERY_TASK_STATUS,
            contextualTask.intent()
        );
    }

    @Test
    void rejectsInvalidIntentAndMissingRequiredCompany() {
        AgentRequestInterpreter invalidIntent = interpreterReturning(
            new AgentIntentPrediction(
                AgentRequestInterpreter.SCHEMA_VERSION,
                "DELETE_ALL_TASKS",
                "北京示例有限公司",
                null,
                1.0D
            )
        );
        AgentRequestInterpreter missingCompany = interpreterReturning(
            new AgentIntentPrediction(
                AgentRequestInterpreter.SCHEMA_VERSION,
                "CREATE_RISK_REPORT_TASK",
                null,
                null,
                1.0D
            )
        );

        assertEquals(
            AgentIntent.UNKNOWN,
            invalidIntent.interpret("帮我看一下", false).intent()
        );
        assertEquals(
            AgentIntent.UNKNOWN,
            missingCompany.interpret("帮我看一下", false).intent()
        );
    }

    private AgentRequestInterpreter interpreterReturning(
        AgentIntentPrediction prediction
    ) {
        AgentIntentModel model = request -> Optional.of(prediction);
        return new AgentRequestInterpreter(
            fallbackParser,
            List.of(model)
        );
    }
}
