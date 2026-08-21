package com.atlas.enterprise.company.refresh;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.company-refresh.xlb")
public class XlbCompanyRefreshProperties {
    private static final List<String> DEFAULT_REQUIRED = Arrays.stream(XlbRefreshCategory.values())
        .filter(XlbRefreshCategory::reportRequired)
        .map(Enum::name)
        .toList();
    private static final List<String> DEFAULT_OPTIONAL = Arrays.stream(XlbRefreshCategory.values())
        .filter(category -> !category.reportRequired())
        .map(Enum::name)
        .toList();

    private boolean enabled;
    private URI baseUrl = URI.create("https://openapi.xiaolanben.com/api/");
    private String accessId;
    private String accessToken;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxAttempts = 3;
    private int pageSize = 100;
    private int maxRecordsPerCategory = 5000;
    private List<String> requiredCategories = DEFAULT_REQUIRED;
    private List<String> optionalCategories = DEFAULT_OPTIONAL;

    void validate() {
        if (!enabled) {
            return;
        }
        if (baseUrl == null || !Set.of("http", "https").contains(baseUrl.getScheme())) {
            throw new IllegalStateException("XLB base-url must use HTTP or HTTPS");
        }
        if (accessId == null || accessId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("XLB credentials must be supplied through environment configuration");
        }
        validateDuration(connectTimeout, "connect-timeout");
        validateDuration(requestTimeout, "request-timeout");
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalStateException("XLB max-attempts must be between 1 and 5");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalStateException("XLB page-size must be between 1 and 100");
        }
        if (maxRecordsPerCategory < 1 || maxRecordsPerCategory > 10_000) {
            throw new IllegalStateException("XLB max-records-per-category must be between 1 and 10000");
        }
        Set<XlbRefreshCategory> required = categories(requiredCategories);
        Set<XlbRefreshCategory> optional = categories(optionalCategories);
        if (!required.contains(XlbRefreshCategory.BASE)) {
            throw new IllegalStateException("XLB required-categories must include BASE");
        }
        Set<XlbRefreshCategory> overlap = new LinkedHashSet<>(required);
        overlap.retainAll(optional);
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("XLB categories cannot be both required and optional: " + overlap);
        }
    }

    Set<XlbRefreshCategory> requiredCategorySet() {
        return categories(requiredCategories);
    }

    Set<XlbRefreshCategory> optionalCategorySet() {
        return categories(optionalCategories);
    }

    private static Set<XlbRefreshCategory> categories(List<String> values) {
        Set<XlbRefreshCategory> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().map(XlbRefreshCategory::parse).forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private static void validateDuration(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()
            || duration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException(
                "XLB " + field + " must be greater than zero and at most two minutes"
            );
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public String getAccessId() { return accessId; }
    public void setAccessId(String accessId) { this.accessId = blankToNull(accessId); }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = blankToNull(accessToken); }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxRecordsPerCategory() { return maxRecordsPerCategory; }
    public void setMaxRecordsPerCategory(int maxRecordsPerCategory) { this.maxRecordsPerCategory = maxRecordsPerCategory; }
    public List<String> getRequiredCategories() { return requiredCategories; }
    public void setRequiredCategories(List<String> requiredCategories) { this.requiredCategories = requiredCategories; }
    public List<String> getOptionalCategories() { return optionalCategories; }
    public void setOptionalCategories(List<String> optionalCategories) { this.optionalCategories = optionalCategories; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
