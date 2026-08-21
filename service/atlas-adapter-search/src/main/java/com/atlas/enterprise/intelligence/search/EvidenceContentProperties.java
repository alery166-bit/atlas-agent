package com.atlas.enterprise.intelligence.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.intelligence.content")
public class EvidenceContentProperties {
    private boolean enabled;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private int maxBytes = 1_048_576;
    private String userAgent = "AtlasEvidenceCollector/1.0";
    private boolean allowPrivateNetwork;

    public void validate() {
        validateDuration(connectTimeout, "connect-timeout");
        validateDuration(requestTimeout, "request-timeout");
        if (maxBytes < 16_384 || maxBytes > 5_242_880) {
            throw new IllegalStateException(
                "Evidence content max-bytes must be between 16384 and 5242880"
            );
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalStateException(
                "Evidence content user-agent is required"
            );
        }
    }

    private static void validateDuration(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()
            || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException(
                "Evidence content " + field
                    + " must be greater than 0 and at most 2 minutes"
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isAllowPrivateNetwork() {
        return allowPrivateNetwork;
    }

    public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }
}
