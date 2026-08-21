package com.atlas.enterprise.configuration.application;

import com.atlas.enterprise.configuration.ConfigurationCategory;
import com.atlas.enterprise.configuration.ConfigurationDefinition;
import com.atlas.enterprise.configuration.ConfigurationVersion;
import com.atlas.enterprise.configuration.ConfigurationVersionStatus;
import com.atlas.enterprise.configuration.ConnectorTestRun;
import com.atlas.enterprise.configuration.port.ConfigurationRepository;
import com.atlas.enterprise.configuration.port.ConnectorTestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorManagementService {
    private final ConfigurationRepository configurations;
    private final ConnectorTestRepository tests;
    private final ConnectorConfigurationCodec codec;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConnectorManagementService(
        ConfigurationRepository configurations,
        ConnectorTestRepository tests,
        ConnectorConfigurationCodec codec,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.configurations = configurations;
        this.tests = tests;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ConnectorTestRun test(
        UUID versionId,
        Map<String, Object> sampleRecord,
        String operatorId
    ) {
        ConfigurationVersion version = requireConnectorVersion(versionId);
        if (version.status() != ConfigurationVersionStatus.DRAFT
            && version.status() != ConfigurationVersionStatus.VALIDATED) {
            throw new ConfigurationConflictException(
                "Only draft or validated connectors can be tested"
            );
        }
        ConnectorConfigurationCodec.ConnectorDefinition definition = codec.parse(version.valueJson());
        long started = System.nanoTime();
        ConnectorTestRun.Status status;
        String message;
        String preview = null;
        try {
            if (!definition.enabled()) {
                status = ConnectorTestRun.Status.PASSED;
                message = "Connector is disabled; schema validation passed";
            } else {
                ProbeResult result = probe(definition);
                status = result.passed()
                    ? ConnectorTestRun.Status.PASSED
                    : ConnectorTestRun.Status.FAILED;
                message = result.message();
            }
            if (sampleRecord != null && !sampleRecord.isEmpty()) {
                preview = objectMapper.writeValueAsString(codec.preview(definition, sampleRecord));
            }
        } catch (Exception exception) {
            status = ConnectorTestRun.Status.FAILED;
            message = safeMessage(exception);
        }
        long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        return tests.save(new ConnectorTestRun(
            UUID.randomUUID(), versionId, version.checksum(), status,
            latency, message, preview, required(operatorId), clock.instant()
        ));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(UUID versionId, Map<String, Object> sampleRecord) {
        ConfigurationVersion version = requireConnectorVersion(versionId);
        if (sampleRecord == null || sampleRecord.isEmpty()) {
            throw new IllegalArgumentException("sampleRecord is required");
        }
        return codec.preview(codec.parse(version.valueJson()), sampleRecord);
    }

    @Transactional(readOnly = true)
    public List<ConnectorTestRun> history(UUID versionId) {
        requireConnectorVersion(versionId);
        return tests.findByVersion(versionId);
    }

    private ProbeResult probe(ConnectorConfigurationCodec.ConnectorDefinition definition)
        throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(definition.connectTimeout()).build();
        URI uri = URI.create(definition.baseUri().toString().replaceAll("/$", "")
            + definition.path());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(definition.requestTimeout())
            .header("Accept", "application/json");
        String credential = resolveCredential(definition.credentialRef());
        if (credential != null) request.header("Authorization", "Bearer " + credential);

        if (definition.category() == ConfigurationCategory.DATA_SOURCE) {
            request.GET();
        } else if (definition.category() == ConfigurationCategory.SEARCH) {
            String body = objectMapper.writeValueAsString(Map.of(
                "query", "企业风险信息",
                "search_depth", definition.settings().path("search_depth").asText("basic"),
                "topic", definition.settings().path("topic").asText("general"),
                "max_results", 1,
                "include_answer", false,
                "include_raw_content", false
            ));
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            String body = objectMapper.writeValueAsString(Map.of(
                "model", definition.settings().path("model").asText(),
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", "返回一个带来源链接的企业公开信息测试结果"
                )),
                "max_tokens", 64
            ));
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = client.send(
            request.build(), HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProbeResult(false, "Endpoint returned HTTP " + response.statusCode());
        }
        JsonNode payload = objectMapper.readTree(response.body());
        if (payload == null) return new ProbeResult(false, "Endpoint returned an empty response");
        return new ProbeResult(true, "Connection succeeded with HTTP " + response.statusCode());
    }

    private ConfigurationVersion requireConnectorVersion(UUID versionId) {
        ConfigurationVersion version = configurations.findVersion(versionId)
            .orElseThrow(() -> new ConfigurationNotFoundException(
                "Configuration version not found: " + versionId
            ));
        ConfigurationDefinition definition = configurations.findDefinitions().stream()
            .filter(item -> item.configId().equals(version.configId()))
            .findFirst().orElseThrow();
        if (definition.category() != ConfigurationCategory.DATA_SOURCE
            && definition.category() != ConfigurationCategory.SEARCH
            && definition.category() != ConfigurationCategory.MODEL) {
            throw new IllegalArgumentException("Configuration is not a connector");
        }
        return version;
    }

    private static String resolveCredential(String reference) {
        if (reference == null) return null;
        if (reference.startsWith("env:")) {
            String name = reference.substring(4);
            String value = System.getenv(name);
            if (value == null || value.isBlank()) value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Server credential reference is not configured");
            }
            return value;
        }
        throw new IllegalArgumentException(
            "This deployment does not yet provide the requested secret backend"
        );
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank()
            ? exception.getClass().getSimpleName()
            : value.substring(0, Math.min(value.length(), 500));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("operatorId is required");
        return value.trim();
    }

    private record ProbeResult(boolean passed, String message) {}
}
