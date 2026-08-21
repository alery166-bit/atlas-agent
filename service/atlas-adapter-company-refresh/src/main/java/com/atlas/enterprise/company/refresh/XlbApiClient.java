package com.atlas.enterprise.company.refresh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class XlbApiClient {
    private static final String ACCESS_ID = "accessId";
    private static final String ACCESS_TOKEN = "accessToken";

    private final XlbCompanyRefreshProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    XlbApiClient(XlbCompanyRefreshProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .build();
    }

    JsonNode object(int apiId, Map<String, String> params, String path) throws IOException {
        JsonNode root = execute(apiId, params);
        JsonNode result = requiredPath(root, path);
        if (!result.isObject()) {
            throw new IOException("XLB response path is not an object: " + path);
        }
        return result;
    }

    List<JsonNode> page(int apiId, Map<String, String> params, String path) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        int pageIndex = 1;
        while (result.size() < properties.getMaxRecordsPerCategory()) {
            Map<String, String> current = new LinkedHashMap<>(params);
            current.put("pageIndex", Integer.toString(pageIndex));
            current.put("pageSize", Integer.toString(properties.getPageSize()));
            JsonNode container = requiredPath(execute(apiId, current), path);
            JsonNode data = requiredChild(container, "data", path + ".data");
            if (!data.isArray()) {
                throw new IOException("XLB response data is not an array: " + path);
            }
            data.forEach(result::add);
            int total = container.path("totalCount").asInt(result.size());
            if (data.size() < properties.getPageSize() || result.size() >= total) {
                break;
            }
            pageIndex++;
        }
        if (result.size() > properties.getMaxRecordsPerCategory()) {
            return List.copyOf(result.subList(0, properties.getMaxRecordsPerCategory()));
        }
        return List.copyOf(result);
    }

    List<JsonNode> rolling(
        int apiId,
        Map<String, String> baseParams,
        String path,
        String cursorResponseField,
        String cursorRequestField
    ) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        String cursor = "";
        while (result.size() < properties.getMaxRecordsPerCategory()) {
            Map<String, String> current = new LinkedHashMap<>(baseParams);
            current.put("pageSize", Integer.toString(properties.getPageSize()));
            current.put(cursorRequestField, cursor);
            JsonNode container = requiredPath(execute(apiId, current), path);
            JsonNode data = requiredChild(container, "data", path + ".data");
            if (!data.isArray()) {
                throw new IOException("XLB rolling response data is not an array: " + path);
            }
            data.forEach(result::add);
            String next = text(container, cursorResponseField);
            if (next == null || next.isBlank() || next.equals(cursor)) {
                break;
            }
            cursor = next;
        }
        if (result.size() > properties.getMaxRecordsPerCategory()) {
            return List.copyOf(result.subList(0, properties.getMaxRecordsPerCategory()));
        }
        return List.copyOf(result);
    }

    private JsonNode execute(int apiId, Map<String, String> params) throws IOException {
        URI uri = uri(apiId, params);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getRequestTimeout())
                .header(ACCESS_ID, properties.getAccessId())
                .header(ACCESS_TOKEN, properties.getAccessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
            try {
                HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("XLB HTTP status " + response.statusCode());
                }
                JsonNode root = objectMapper.readTree(response.body());
                validateProviderResponse(root);
                return root;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("XLB request interrupted", exception);
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt < properties.getMaxAttempts()) {
                    backoff(attempt);
                }
            }
        }
        throw lastFailure == null ? new IOException("XLB request failed") : lastFailure;
    }

    private URI uri(int apiId, Map<String, String> params) {
        StringBuilder value = new StringBuilder(properties.getBaseUrl().resolve(Integer.toString(apiId)).toString());
        if (params != null && !params.isEmpty()) {
            value.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    value.append('&');
                }
                first = false;
                value.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return URI.create(value.toString());
    }

    private static void validateProviderResponse(JsonNode root) throws IOException {
        JsonNode code = root.get("code");
        if (code == null || code.isNull() || "0".equals(code.asText())) {
            return;
        }
        String message = root.path("message").asText("XLB provider rejected the request");
        throw new IOException("XLB provider error " + code.asText() + ": " + message);
    }

    private static JsonNode requiredPath(JsonNode root, String path) throws IOException {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            current = current.path(part);
        }
        if (current.isMissingNode() || current.isNull()) {
            throw new IOException("XLB response path is missing: " + path);
        }
        return current;
    }

    private static JsonNode requiredChild(JsonNode node, String child, String path) throws IOException {
        JsonNode value = node.path(child);
        if (value.isMissingNode() || value.isNull()) {
            throw new IOException("XLB response path is missing: " + path);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static void backoff(int attempt) throws IOException {
        try {
            Thread.sleep(Duration.ofMillis(250L * attempt).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("XLB retry backoff interrupted", exception);
        }
    }
}
