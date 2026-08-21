package com.atlas.enterprise.model;

import com.atlas.enterprise.intelligence.application.ModelUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class OpenAiCompatibleChatClient {
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    OpenAiCompatibleChatClient(ObjectMapper objectMapper, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    JsonNode complete(
        PublishedModelConnectorResolver.ResolvedModel model,
        String systemPrompt,
        Object userPayload
    ) {
        return completeWithUsage(model, systemPrompt, userPayload).content();
    }

    Completion completeWithUsage(
        PublishedModelConnectorResolver.ResolvedModel model,
        String systemPrompt,
        Object userPayload
    ) {
        var definition = model.definition();
        String modelName = definition.settings().path("model").asText();
        URI endpoint = URI.create(
            definition.baseUri().toString().replaceAll("/$", "") + definition.path()
        );
        RuntimeException last = null;
        boolean jsonRepairRequested = false;
        int transientAttempts = 0;
        int maximumCalls = definition.maxAttempts() + 1;
        for (int call = 1; call <= maximumCalls; call++) {
            String effectiveSystemPrompt = jsonRepairRequested
                ? systemPrompt + "\n上一次输出不是合法JSON。本次必须只输出一个完整、可解析的JSON对象，不要使用Markdown代码块或补充说明。"
                : systemPrompt;
            Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                    Map.of("role", "system", "content", effectiveSystemPrompt),
                    Map.of("role", "user", "content", write(userPayload))
                ),
                "temperature", definition.settings().path("temperature").asDouble(0.1D),
                "max_tokens", definition.settings().path("max_tokens").asInt(2048),
                "stream", false
            );
            long started = System.nanoTime();
            boolean responseRecorded = false;
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(definition.connectTimeout())
                    .build();
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(definition.requestTimeout())
                    .header("Authorization", "Bearer " + model.credential())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                record(modelName, response.statusCode(), started);
                responseRecorded = true;
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode root = objectMapper.readTree(response.body());
                    String content = root.path("choices").path(0)
                        .path("message").path("content").asText(null);
                    if (content == null || content.isBlank()) {
                        throw new IllegalStateException("Model response does not contain message content");
                    }
                    ModelUsage usage = usage(root);
                    recordUsage(modelName, usage);
                    try {
                        return new Completion(
                            objectMapper.readTree(stripFence(content)),
                            usage
                        );
                    } catch (Exception invalidJson) {
                        meters.counter(
                            "atlas.model.invalid_json",
                            "model",
                            modelName,
                            "recovered",
                            Boolean.toString(!jsonRepairRequested)
                        ).increment();
                        last = new IllegalStateException(
                            "Model response is not valid JSON",
                            invalidJson
                        );
                        if (!jsonRepairRequested) {
                            jsonRepairRequested = true;
                            continue;
                        }
                        break;
                    }
                }
                last = new IllegalStateException(
                    "Model endpoint returned HTTP " + response.statusCode()
                );
                if (response.statusCode() != 429 && response.statusCode() < 500) break;
                transientAttempts++;
                if (transientAttempts >= definition.maxAttempts()) break;
            } catch (InterruptedException exception) {
                if (!responseRecorded) {
                    recordFailure(modelName, "interrupted", started);
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Model request was interrupted", exception);
            } catch (Exception exception) {
                if (!responseRecorded) {
                    recordFailure(
                        modelName,
                        exception instanceof java.net.http.HttpTimeoutException
                            ? "timeout"
                            : "error",
                        started
                    );
                }
                last = exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Model request failed", exception);
                transientAttempts++;
                if (transientAttempts >= definition.maxAttempts()) break;
            }
            backoff(Duration.ofMillis((long) definition.backoffMs() * call));
        }
        throw last == null ? new IllegalStateException("Model request failed") : last;
    }

    private void record(String model, int status, long started) {
        recordOutcome(
            model,
            status >= 200 && status < 300 ? "success" : "http_" + status,
            started
        );
    }

    private void recordFailure(String model, String outcome, long started) {
        recordOutcome(model, outcome, started);
    }

    private void recordOutcome(String model, String outcome, long started) {
        meters.counter("atlas.model.calls", "model", model, "outcome", outcome)
            .increment();
        Timer.builder("atlas.model.duration")
            .tag("model", model)
            .register(meters)
            .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private void recordUsage(String model, ModelUsage usage) {
        incrementTokens(model, "prompt", usage.promptTokens());
        incrementTokens(model, "completion", usage.completionTokens());
        incrementTokens(model, "total", usage.totalTokens());
    }

    private void incrementTokens(String model, String type, int amount) {
        if (amount <= 0) return;
        meters.counter("atlas.model.tokens", "model", model, "type", type)
            .increment(amount);
    }

    private static ModelUsage usage(JsonNode root) {
        JsonNode usage = root.path("usage");
        return new ModelUsage(
            1,
            Math.max(0, usage.path("prompt_tokens").asInt(0)),
            Math.max(0, usage.path("completion_tokens").asInt(0)),
            Math.max(0, usage.path("total_tokens").asInt(0))
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize model request", exception);
        }
    }

    private static String stripFence(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            int firstBreak = normalized.indexOf('\n');
            int lastFence = normalized.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                normalized = normalized.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return normalized;
    }

    private static void backoff(Duration duration) {
        if (duration.isZero()) return;
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model retry was interrupted", exception);
        }
    }

    record Completion(JsonNode content, ModelUsage usage) {
    }
}
