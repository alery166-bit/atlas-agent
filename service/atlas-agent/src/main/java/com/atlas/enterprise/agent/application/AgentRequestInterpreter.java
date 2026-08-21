package com.atlas.enterprise.agent.application;

import com.atlas.enterprise.agent.port.AgentIntentModel;
import com.atlas.enterprise.agent.port.AgentIntentModelRequest;
import com.atlas.enterprise.agent.port.AgentIntentPrediction;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AgentRequestInterpreter {
    public static final String SCHEMA_VERSION = "agent-intent.v1";
    private static final double MINIMUM_CONFIDENCE = 0.70D;
    private static final int MAXIMUM_COMPANY_QUERY_LENGTH = 120;
    private static final int MAXIMUM_TASK_REFERENCE_LENGTH = 128;

    private final AgentRequestParser fallbackParser;
    private final List<AgentIntentModel> models;

    public AgentRequestInterpreter(
        AgentRequestParser fallbackParser,
        List<AgentIntentModel> models
    ) {
        this.fallbackParser = fallbackParser;
        this.models = List.copyOf(models);
    }

    public ParsedAgentRequest interpret(String message, boolean hasTaskId) {
        ParsedAgentRequest fallback = fallbackParser.parse(message, hasTaskId);
        List<AgentIntentModel> availableModels = models.stream()
            .filter(AgentIntentModel::available)
            .toList();
        if (isDeterministicGuard(fallback, hasTaskId) || availableModels.isEmpty()) {
            return fallback;
        }

        AgentIntentModelRequest request = new AgentIntentModelRequest(
            SCHEMA_VERSION,
            message,
            hasTaskId,
            Arrays.stream(AgentIntent.values()).map(Enum::name).toList()
        );
        try {
            Optional<AgentIntentPrediction> prediction = availableModels
                .getFirst()
                .predict(request);
            return prediction
                .flatMap(value -> validate(value, hasTaskId))
                .orElse(fallback);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean isDeterministicGuard(
        ParsedAgentRequest fallback,
        boolean hasTaskId
    ) {
        return fallback.intent() == AgentIntent.UNSUPPORTED_SCOPE
            || hasTaskId
            || fallback.taskReference() != null;
    }

    private static Optional<ParsedAgentRequest> validate(
        AgentIntentPrediction prediction,
        boolean hasTaskId
    ) {
        if (
            !SCHEMA_VERSION.equals(prediction.schemaVersion())
                || prediction.confidence() == null
                || prediction.confidence() < MINIMUM_CONFIDENCE
                || prediction.confidence() > 1.0D
        ) {
            return Optional.empty();
        }

        AgentIntent intent;
        try {
            intent = AgentIntent.valueOf(
                prediction.intent().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
        if (intent == AgentIntent.UNKNOWN) {
            return Optional.empty();
        }

        String companyQuery = normalize(
            prediction.companyQuery(),
            MAXIMUM_COMPANY_QUERY_LENGTH
        );
        String taskReference = normalize(
            prediction.taskReference(),
            MAXIMUM_TASK_REFERENCE_LENGTH
        );
        if (
            intent == AgentIntent.CREATE_RISK_REPORT_TASK
                && (companyQuery == null || hasTaskId)
        ) {
            return Optional.empty();
        }
        if (
            intent == AgentIntent.QUERY_TASK_STATUS
                && !hasTaskId
                && companyQuery == null
                && taskReference == null
        ) {
            return Optional.empty();
        }
        return Optional.of(new ParsedAgentRequest(
            intent,
            companyQuery,
            taskReference
        ));
    }

    private static String normalize(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (
            normalized.isBlank()
                || normalized.length() > maximumLength
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0
        ) {
            return null;
        }
        return normalized;
    }
}
