package com.atlas.enterprise.company.es;

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
import java.util.Base64;

final class ElasticsearchRestClient {
    private final URI baseUrl;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String authorization;

    ElasticsearchRestClient(
        ElasticsearchDataProperties properties,
        ObjectMapper objectMapper
    ) {
        this.baseUrl = properties.getBaseUrl();
        this.requestTimeout = properties.getRequestTimeout();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.authorization = authorization(properties);
    }

    JsonNode search(String alias, JsonNode body, String routing) throws IOException {
        String path = "/" + alias + "/_search";
        if (routing != null && !routing.isBlank()) {
            path += "?routing=" + URLEncoder.encode(routing, StandardCharsets.UTF_8);
        }
        return request(path, body);
    }

    private JsonNode request(String path, JsonNode body) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body),
                StandardCharsets.UTF_8
            ));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(
                    "Elasticsearch returned HTTP " + response.statusCode()
                        + ": " + abbreviate(response.body())
                );
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Elasticsearch request was interrupted", exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Elasticsearch response was invalid", exception);
        }
    }

    private URI resolve(String path) {
        String root = baseUrl.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return URI.create(root + path);
    }

    private static String authorization(ElasticsearchDataProperties properties) {
        if (properties.getApiKey() != null) {
            return "ApiKey " + properties.getApiKey();
        }
        if (properties.getUsername() == null) {
            return null;
        }
        String credentials = properties.getUsername() + ":" + properties.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
