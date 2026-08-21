package com.atlas.enterprise.company.es;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.data.es")
public class ElasticsearchDataProperties {
    private URI baseUrl = URI.create("http://127.0.0.1:9200");
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private int maxRecords = 5000;
    private String companyAlias = "atlas-company-read";
    private String eventAlias = "atlas-company-event-read";
    private String publicIntelAlias = "atlas-public-intel-read";
    private String contactAlias = "atlas-company-contact-read";
    private String relationAlias = "atlas-company-relation-read";
    private String username;
    private String password;
    private String apiKey;

    void validate() {
        if (baseUrl == null
            || (!"http".equalsIgnoreCase(baseUrl.getScheme())
                && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalStateException("Atlas ES base-url must use HTTP or HTTPS");
        }
        validateDuration(connectTimeout, "connect-timeout");
        validateDuration(requestTimeout, "request-timeout");
        if (maxRecords < 1 || maxRecords > 10_000) {
            throw new IllegalStateException("Atlas ES max-records must be between 1 and 10000");
        }
        validateAlias(companyAlias, "company-alias");
        validateAlias(eventAlias, "event-alias");
        validateAlias(publicIntelAlias, "public-intel-alias");
        validateAlias(contactAlias, "contact-alias");
        validateAlias(relationAlias, "relation-alias");
        if ((username == null) != (password == null)) {
            throw new IllegalStateException("Atlas ES username and password must be configured together");
        }
        if (apiKey != null && !apiKey.isBlank() && username != null) {
            throw new IllegalStateException("Atlas ES api-key and username/password are mutually exclusive");
        }
    }

    private static void validateDuration(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()
            || duration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException(
                "Atlas ES " + field + " must be greater than zero and at most two minutes"
            );
        }
    }

    private static void validateAlias(String alias, String field) {
        if (alias == null || !alias.matches("[a-z0-9._-]+")) {
            throw new IllegalStateException("Atlas ES " + field + " is invalid");
        }
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
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

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    public String getCompanyAlias() {
        return companyAlias;
    }

    public void setCompanyAlias(String companyAlias) {
        this.companyAlias = companyAlias;
    }

    public String getEventAlias() {
        return eventAlias;
    }

    public void setEventAlias(String eventAlias) {
        this.eventAlias = eventAlias;
    }

    public String getPublicIntelAlias() {
        return publicIntelAlias;
    }

    public void setPublicIntelAlias(String publicIntelAlias) {
        this.publicIntelAlias = publicIntelAlias;
    }

    public String getContactAlias() {
        return contactAlias;
    }

    public void setContactAlias(String contactAlias) {
        this.contactAlias = contactAlias;
    }

    public String getRelationAlias() {
        return relationAlias;
    }

    public void setRelationAlias(String relationAlias) {
        this.relationAlias = relationAlias;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = blankToNull(username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = blankToNull(password);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = blankToNull(apiKey);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
