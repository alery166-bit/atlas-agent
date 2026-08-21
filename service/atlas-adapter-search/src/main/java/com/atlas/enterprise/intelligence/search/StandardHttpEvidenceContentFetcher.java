package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.EvidenceContentCapture;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.port.EvidenceContentFetcher;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

public class StandardHttpEvidenceContentFetcher
    implements EvidenceContentFetcher {
    private final EvidenceContentProperties properties;
    private final HttpClient httpClient;

    public StandardHttpEvidenceContentFetcher(
        EvidenceContentProperties properties
    ) {
        properties.validate();
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public EvidenceContentCapture fetch(String url) {
        Instant capturedAt = Instant.now();
        try {
            URI uri = validateUrl(url);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getRequestTimeout())
                .header("Accept", "text/html,text/plain,application/xhtml+xml")
                .header("User-Agent", properties.getUserAgent())
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return EvidenceContentCapture.failed(
                        url,
                        httpFailureCode(response.statusCode()),
                        "Evidence page returned HTTP " + response.statusCode(),
                        capturedAt
                    );
                }
                String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");
                if (!isTextContent(contentType)) {
                    return EvidenceContentCapture.failed(
                        url,
                        "UNSUPPORTED_CONTENT_TYPE",
                        "Unsupported evidence content type: " + contentType,
                        capturedAt
                    );
                }
                byte[] bytes = body.readNBytes(properties.getMaxBytes() + 1);
                boolean truncated = bytes.length > properties.getMaxBytes();
                int length = Math.min(bytes.length, properties.getMaxBytes());
                byte[] retained = java.util.Arrays.copyOf(bytes, length);
                String raw = new String(retained, charset(contentType));
                String extracted = contentType.toLowerCase(Locale.ROOT)
                    .contains("html")
                    ? HtmlTextExtractor.extract(raw)
                    : raw.replaceAll("\\s+", " ").trim();
                if (extracted.isBlank()) {
                    return EvidenceContentCapture.failed(
                        url,
                        "CONTENT_TEXT_EMPTY",
                        "Evidence page did not contain readable text",
                        capturedAt
                    );
                }
                return new EvidenceContentCapture(
                    EvidenceContentStatus.CAPTURED,
                    url,
                    uri.toString(),
                    response.statusCode(),
                    contentType,
                    raw,
                    extracted,
                    sha256(retained),
                    sha256(extracted.getBytes(StandardCharsets.UTF_8)),
                    retained.length,
                    truncated,
                    null,
                    null,
                    capturedAt
                );
            }
        } catch (UnsafeEvidenceUrlException exception) {
            return EvidenceContentCapture.failed(
                url,
                exception.code(),
                exception.getMessage(),
                capturedAt
            );
        } catch (HttpTimeoutException exception) {
            return EvidenceContentCapture.failed(
                url,
                "CONTENT_FETCH_TIMEOUT",
                "Evidence page request timed out",
                capturedAt
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return EvidenceContentCapture.failed(
                url,
                "CONTENT_FETCH_INTERRUPTED",
                "Evidence page request was interrupted",
                capturedAt
            );
        } catch (IOException exception) {
            return EvidenceContentCapture.failed(
                url,
                "CONTENT_FETCH_IO_ERROR",
                exception.getClass().getSimpleName(),
                capturedAt
            );
        }
    }

    private URI validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new UnsafeEvidenceUrlException(
                "INVALID_CONTENT_URL",
                "Evidence URL is invalid"
            );
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
            && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new UnsafeEvidenceUrlException(
                "UNSUPPORTED_CONTENT_URL",
                "Evidence URL must use HTTP or HTTPS"
            );
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new UnsafeEvidenceUrlException(
                "INVALID_CONTENT_URL",
                "Evidence URL host is invalid"
            );
        }
        if (!properties.isAllowPrivateNetwork()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                        throw new UnsafeEvidenceUrlException(
                            "PRIVATE_NETWORK_BLOCKED",
                            "Evidence URL resolves to a private or local network"
                        );
                    }
                }
            } catch (UnknownHostException exception) {
                throw new UnsafeEvidenceUrlException(
                    "CONTENT_HOST_UNRESOLVED",
                    "Evidence URL host could not be resolved"
                );
            }
        }
        return uri;
    }

    private static boolean isTextContent(String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/html")
            || normalized.startsWith("text/plain")
            || normalized.startsWith("application/xhtml+xml");
    }

    private static Charset charset(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(trimmed.substring("charset=".length()));
                } catch (RuntimeException ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String httpFailureCode(int status) {
        return switch (status) {
            case 401, 403 -> "CONTENT_ACCESS_DENIED";
            case 404 -> "CONTENT_NOT_FOUND";
            case 429 -> "CONTENT_RATE_LIMITED";
            default -> status >= 500
                ? "CONTENT_UPSTREAM_5XX"
                : status >= 300
                    ? "CONTENT_REDIRECT_NOT_FOLLOWED"
                    : "CONTENT_HTTP_ERROR";
        };
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class UnsafeEvidenceUrlException
        extends RuntimeException {
        private final String code;

        private UnsafeEvidenceUrlException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
