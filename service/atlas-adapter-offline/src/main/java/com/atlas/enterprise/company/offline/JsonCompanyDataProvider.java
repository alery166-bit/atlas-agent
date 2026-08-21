package com.atlas.enterprise.company.offline;

import com.atlas.enterprise.company.CompanyCandidate;
import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.CompanyQuery;
import com.atlas.enterprise.company.CompanyResolution;
import com.atlas.enterprise.company.CompanyResolutionStatus;
import com.atlas.enterprise.company.QueryStatus;
import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceResult;
import com.atlas.enterprise.company.SourceStatus;
import com.atlas.enterprise.company.port.CompanyDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class JsonCompanyDataProvider implements CompanyDataProvider {
    static final String SOURCE_SYSTEM = "OFFLINE_JSON";

    private final OfflineDataProperties properties;
    private final OfflineResourceAccess resources;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JsonCompanyDataProvider(
        OfflineDataProperties properties,
        OfflineResourceAccess resources,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.properties = properties;
        this.resources = resources;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String providerName() {
        return SOURCE_SYSTEM;
    }

    @Override
    public CompanyResolution resolve(CompanyQuery query) {
        Instant fetchedAt = clock.instant();
        List<CompanyCandidate> candidates = new ArrayList<>();
        List<SourceStatus> statuses = new ArrayList<>();
        for (String location : properties.getJsonFiles()) {
            try {
                List<JsonNode> sources = sourceNodes(location);
                long matches = 0;
                Instant dataAsOf = null;
                for (JsonNode source : sources) {
                    if (matches(source, query)) {
                        candidates.add(candidate(source, query));
                        matches++;
                        dataAsOf = OfflineTimeParser.later(dataAsOf, dataAsOf(source));
                    }
                }
                statuses.add(successStatus(location, matches, dataAsOf, fetchedAt));
            } catch (IOException exception) {
                statuses.add(failedStatus(location, fetchedAt, exception));
            }
        }
        boolean allFailed = !statuses.isEmpty() && statuses.stream().allMatch(SourceStatus::failed);
        CompanyResolutionStatus status = allFailed
            ? CompanyResolutionStatus.FAILED
            : candidates.isEmpty()
                ? CompanyResolutionStatus.NOT_FOUND
                : candidates.size() == 1
                    ? CompanyResolutionStatus.UNIQUE
                    : CompanyResolutionStatus.AMBIGUOUS;
        return new CompanyResolution(status, candidates, statuses);
    }

    @Override
    public SourceResult<CompanyFacts> loadFacts(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            JsonSourceMatch match = find(company);
            CompanyFacts facts = facts(match.source(), fetchedAt);
            return new SourceResult<>(
                QueryStatus.SUCCESS_WITH_RESULTS,
                List.of(facts),
                List.of(successStatus(match.location(), 1, facts.dataAsOf(), fetchedAt))
            );
        } catch (IOException exception) {
            return failedResult("configured-json-files", fetchedAt, exception);
        }
    }

    @Override
    public SourceResult<CompanyChange> loadChanges(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        return new SourceResult<>(
            QueryStatus.SUCCESS_EMPTY,
            List.of(),
            List.of(successStatus("json-company-changes", 0, null, fetchedAt))
        );
    }

    @Override
    public SourceResult<RiskEvent> loadRiskEvents(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            JsonSourceMatch match = find(company);
            JsonNode recentLabels = match.source().path("recentAddRiskLabel");
            List<RiskEvent> events = new ArrayList<>();
            if (recentLabels.isArray()) {
                for (JsonNode label : recentLabels) {
                    String riskLabel = text(label, "riskLabel");
                    String labelTime = text(label, "labelTime");
                    String sourceRecordId = firstText(
                        text(label, "fromId"),
                        riskLabel + "-" + labelTime
                    );
                    events.add(new RiskEvent(
                        normalizeRiskType(riskLabel),
                        SOURCE_SYSTEM,
                        match.location(),
                        sourceRecordId,
                        labelTime,
                        riskLabel,
                        "type=" + value(text(label, "type")),
                        OfflineTimeParser.parse(labelTime),
                        fetchedAt,
                        simpleFields(label)
                    ));
                }
            }
            return new SourceResult<>(
                events.isEmpty() ? QueryStatus.SUCCESS_EMPTY : QueryStatus.SUCCESS_WITH_RESULTS,
                events,
                List.of(successStatus(
                    match.location() + "#recentAddRiskLabel",
                    events.size(),
                    events.stream()
                        .map(RiskEvent::dataAsOf)
                        .reduce(null, OfflineTimeParser::later),
                    fetchedAt
                ))
            );
        } catch (IOException exception) {
            return failedResult("configured-json-files", fetchedAt, exception);
        }
    }

    private JsonSourceMatch find(ResolvedCompany company) throws IOException {
        for (String location : properties.getJsonFiles()) {
            for (JsonNode source : sourceNodes(location)) {
                if (company.sourceEntityId().equals(sourceId(source))) {
                    return new JsonSourceMatch(location, source);
                }
            }
        }
        throw new IOException("Company source record not found in configured JSON files");
    }

    private List<JsonNode> sourceNodes(String location) throws IOException {
        Resource resource = resources.location(location);
        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("JSON source is not readable: " + resource.getDescription());
        }
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            JsonNode hits = root.path("hits").path("hits");
            List<JsonNode> sources = new ArrayList<>();
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    JsonNode source = hit.path("_source");
                    if (source.isObject()) {
                        sources.add(source);
                    }
                }
            } else if (root.isObject()) {
                sources.add(root);
            }
            return sources;
        }
    }

    private static boolean matches(JsonNode source, CompanyQuery query) {
        String actual = query.looksLikeUnifiedCreditCode()
            ? firstText(text(source, "socialIdentifier"), text(source, "taxpayerIdentifier"))
            : firstText(text(source, "fullName"), text(source, "companyName"));
        return actual != null && actual.equalsIgnoreCase(query.value());
    }

    private static CompanyCandidate candidate(JsonNode source, CompanyQuery query) {
        return new CompanyCandidate(
            SOURCE_SYSTEM,
            sourceId(source),
            firstText(text(source, "fullName"), text(source, "companyName")),
            firstText(text(source, "socialIdentifier"), text(source, "taxpayerIdentifier")),
            text(source, "registerNum"),
            text(source, "corporation"),
            text(source, "operateStatus"),
            firstText(text(source, "qccAddress"), text(source, "address")),
            query.looksLikeUnifiedCreditCode() ? BigDecimal.ONE : new BigDecimal("0.9800"),
            dataAsOf(source),
            selectedFields(source)
        );
    }

    private static CompanyFacts facts(JsonNode source, Instant fetchedAt) {
        return new CompanyFacts(
            firstText(text(source, "fullName"), text(source, "companyName")),
            firstText(text(source, "socialIdentifier"), text(source, "taxpayerIdentifier")),
            text(source, "registerNum"),
            text(source, "corporation"),
            text(source, "operateStatus"),
            firstText(text(source, "qccAddress"), text(source, "address")),
            text(source, "companyType"),
            String.join(" ", value(text(source, "capital")), value(text(source, "unit"))).trim(),
            firstText(text(source, "businessStartTime"), text(source, "openDate")),
            text(source, "registerAuthority"),
            text(source, "businessScope"),
            text(source, "industry"),
            SOURCE_SYSTEM,
            sourceId(source),
            dataAsOf(source),
            fetchedAt,
            selectedFields(source)
        );
    }

    private static Map<String, String> selectedFields(JsonNode source) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "actualController", text(source, "actualController"));
        put(fields, "personScale", text(source, "personScale"));
        put(fields, "insuredNum", text(source, "insuredNum"));
        put(fields, "telephone", text(source, "telephone"));
        put(fields, "mailbox", text(source, "mailbox"));
        put(fields, "businessTerm", text(source, "businessTerm"));
        put(fields, "briefly", text(source, "briefly"));
        put(fields, "riskScore", text(source, "riskScore"));
        put(fields, "riskLabels", rawValue(source, "riskLabel"));
        put(fields, "industryIds", rawValue(source, "industryIds"));
        put(fields, "paidCapital", text(source, "payedCapital"));
        put(fields, "listingInfo", text(source, "listingInfo"));
        put(fields, "isMonitor", text(source, "isMonitor"));
        put(fields, "legacyFeatureCompleteness", text(source, "legacyFeatureCompleteness"));
        put(fields, "legacyScoringProfile", text(source, "legacyScoringProfile"));
        put(fields, "shortName", text(source, "shortName"));
        put(fields, "formerNames", rawValue(source, "formerNames"));
        put(fields, "aliases", rawValue(source, "aliases"));
        put(fields, "brands", rawValue(source, "brands"));
        put(fields, "storeNames", rawValue(source, "storeNames"));
        put(fields, "websiteNames", rawValue(source, "websiteNames"));
        put(fields, "socialNames", rawValue(source, "socialNames"));
        return fields;
    }

    private static Map<String, String> simpleFields(JsonNode node) {
        Map<String, String> fields = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode()) {
                fields.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return fields;
    }

    private static Instant dataAsOf(JsonNode source) {
        return OfflineTimeParser.later(
            OfflineTimeParser.parse(text(source, "updateTime")),
            OfflineTimeParser.parse(text(source, "checkDate"))
        );
    }

    private static String sourceId(JsonNode source) {
        return firstText(text(source, "companyId"), text(source, "indexId"), text(source, "uniqueId"));
    }

    private static String normalizeRiskType(String label) {
        if (label == null || label.isBlank()) {
            return "OTHER";
        }
        return switch (label) {
            case "失联" -> "OUT_OF_CONTACT";
            case "拖欠工资", "欠薪" -> "WAGE_ARREARS";
            case "闭店", "门店关闭", "门店闭店" -> "STORE_CLOSURE";
            default -> "OTHER";
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String rawValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static void put(Map<String, String> fields, String key, String fieldValue) {
        if (fieldValue != null && !fieldValue.isBlank()) {
            fields.put(key, fieldValue);
        }
    }

    private static String firstText(String... values) {
        for (String candidate : values) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }

    private static SourceStatus successStatus(
        String sourceName,
        long count,
        Instant dataAsOf,
        Instant fetchedAt
    ) {
        return new SourceStatus(
            SOURCE_SYSTEM,
            sourceName,
            count == 0 ? QueryStatus.SUCCESS_EMPTY : QueryStatus.SUCCESS_WITH_RESULTS,
            count,
            dataAsOf,
            fetchedAt,
            null,
            null
        );
    }

    private static SourceStatus failedStatus(
        String sourceName,
        Instant fetchedAt,
        Exception exception
    ) {
        return new SourceStatus(
            SOURCE_SYSTEM,
            sourceName,
            QueryStatus.FAILED,
            0,
            null,
            fetchedAt,
            "STRUCTURED_SOURCE_QUERY_FAILED",
            exception.getMessage()
        );
    }

    private static <T> SourceResult<T> failedResult(
        String sourceName,
        Instant fetchedAt,
        Exception exception
    ) {
        return new SourceResult<>(
            QueryStatus.FAILED,
            List.of(),
            List.of(failedStatus(sourceName, fetchedAt, exception))
        );
    }

    private record JsonSourceMatch(String location, JsonNode source) {
    }
}
