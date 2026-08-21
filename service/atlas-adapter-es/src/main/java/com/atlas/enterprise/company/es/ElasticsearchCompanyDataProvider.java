package com.atlas.enterprise.company.es;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ElasticsearchCompanyDataProvider implements CompanyDataProvider {
    static final String SOURCE_SYSTEM = "ELASTICSEARCH";

    private final ElasticsearchDataProperties properties;
    private final ObjectMapper objectMapper;
    private final ElasticsearchRestClient client;
    private final Clock clock;

    ElasticsearchCompanyDataProvider(
        ElasticsearchDataProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = new ElasticsearchRestClient(properties, objectMapper);
        this.clock = clock;
    }

    @Override
    public String providerName() {
        return SOURCE_SYSTEM;
    }

    @Override
    public CompanyResolution resolve(CompanyQuery query) {
        Instant fetchedAt = clock.instant();
        String field = query.looksLikeUnifiedCreditCode()
            ? "identity.credit_code"
            : "name.canonical.raw";
        try {
            JsonNode response = client.search(
                properties.getCompanyAlias(),
                exactSearch(field, query.value(), 10),
                null
            );
            List<JsonNode> hits = hits(response);
            long total = totalHits(response, hits.size());
            List<CompanyCandidate> candidates = hits.stream()
                .map(hit -> candidate(hit.path("_source"), query, fetchedAt))
                .toList();
            Instant dataAsOf = candidates.stream()
                .map(CompanyCandidate::dataAsOf)
                .reduce(null, ElasticsearchCompanyDataProvider::later);
            SourceStatus sourceStatus = successStatus(
                properties.getCompanyAlias(),
                total,
                dataAsOf,
                fetchedAt
            );
            CompanyResolutionStatus status = total == 0
                ? CompanyResolutionStatus.NOT_FOUND
                : total == 1
                    ? CompanyResolutionStatus.UNIQUE
                    : CompanyResolutionStatus.AMBIGUOUS;
            return new CompanyResolution(status, candidates, List.of(sourceStatus));
        } catch (IOException exception) {
            return new CompanyResolution(
                CompanyResolutionStatus.FAILED,
                List.of(),
                List.of(failedStatus(properties.getCompanyAlias(), fetchedAt, exception))
            );
        }
    }

    @Override
    public SourceResult<CompanyFacts> loadFacts(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            JsonNode response = client.search(
                properties.getCompanyAlias(),
                exactSearch("atlas_company_id", company.sourceEntityId(), 2),
                null
            );
            List<JsonNode> hits = hits(response);
            if (hits.isEmpty()) {
                return result(
                    List.of(),
                    properties.getCompanyAlias(),
                    null,
                    fetchedAt
                );
            }
            CompanyContext context = loadContext(company.sourceEntityId(), fetchedAt);
            CompanyFacts record = facts(
                hits.getFirst().path("_source"),
                fetchedAt,
                context.additionalFields()
            );
            List<SourceStatus> statuses = new ArrayList<>();
            statuses.add(successStatus(
                properties.getCompanyAlias(),
                hits.size(),
                record.dataAsOf(),
                fetchedAt
            ));
            statuses.addAll(context.sourceStatuses());
            return new SourceResult<>(QueryStatus.SUCCESS_WITH_RESULTS, List.of(record), statuses);
        } catch (IOException exception) {
            return failedResult("elasticsearch-company-context", fetchedAt, exception);
        }
    }

    @Override
    public SourceResult<CompanyChange> loadChanges(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            JsonNode response = client.search(
                properties.getEventAlias(),
                eventSearch(company.sourceEntityId(), "event_type", "REGISTRATION_CHANGE"),
                company.sourceEntityId()
            );
            List<CompanyChange> records = hits(response).stream()
                .map(hit -> change(hit.path("_source"), fetchedAt))
                .toList();
            return result(
                records,
                properties.getEventAlias() + "#registration-change",
                records.stream().map(CompanyChange::dataAsOf).reduce(null, ElasticsearchCompanyDataProvider::later),
                fetchedAt
            );
        } catch (IOException exception) {
            return failedResult(properties.getEventAlias(), fetchedAt, exception);
        }
    }

    @Override
    public SourceResult<RiskEvent> loadRiskEvents(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            JsonNode response = client.search(
                properties.getEventAlias(),
                eventSearch(company.sourceEntityId(), "risk_relevant", true),
                company.sourceEntityId()
            );
            List<RiskEvent> records = hits(response).stream()
                .map(hit -> riskEvent(hit.path("_source"), fetchedAt))
                .toList();
            return result(
                records,
                properties.getEventAlias() + "#risk-relevant",
                records.stream().map(RiskEvent::dataAsOf).reduce(null, ElasticsearchCompanyDataProvider::later),
                fetchedAt
            );
        } catch (IOException exception) {
            return failedResult(properties.getEventAlias(), fetchedAt, exception);
        }
    }

    private ObjectNode exactSearch(String field, Object value, int size) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", size);
        body.put("track_total_hits", true);
        body.putObject("query").putObject("term").set(field, objectMapper.valueToTree(value));
        return body;
    }

    private ObjectNode eventSearch(String companyId, String field, Object value) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", properties.getMaxRecords());
        body.put("track_total_hits", true);
        var bool = body.putObject("query").putObject("bool");
        var filter = bool.putArray("filter");
        filter.addObject().putObject("term").put("atlas_company_id", companyId);
        filter.addObject().putObject("term").set(field, objectMapper.valueToTree(value));
        bool.putArray("must_not").addObject().putObject("term").put("ingest.deleted", true);
        body.putArray("sort")
            .addObject()
            .putObject("event_date")
            .put("order", "desc")
            .put("unmapped_type", "date");
        return body;
    }

    private ObjectNode childSearch(String companyId, int size, String sortField) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", size);
        body.put("track_total_hits", true);
        var bool = body.putObject("query").putObject("bool");
        bool.putArray("filter")
            .addObject()
            .putObject("term")
            .put("atlas_company_id", companyId);
        bool.putArray("must_not")
            .addObject()
            .putObject("term")
            .put("ingest.deleted", true);
        if (sortField != null) {
            body.putArray("sort")
                .addObject()
                .putObject(sortField)
                .put("order", "desc")
                .put("unmapped_type", "date");
        }
        return body;
    }

    private CompanyContext loadContext(String companyId, Instant fetchedAt) throws IOException {
        JsonNode contactsResponse = client.search(
            properties.getContactAlias(),
            childSearch(companyId, 100, "source.source_updated_at"),
            companyId
        );
        JsonNode intelligenceResponse = client.search(
            properties.getPublicIntelAlias(),
            childSearch(companyId, 5, "published_at"),
            companyId
        );
        JsonNode relationsResponse = client.search(
            properties.getRelationAlias(),
            childSearch(companyId, properties.getMaxRecords(), "source.source_updated_at"),
            companyId
        );
        List<JsonNode> contacts = hits(contactsResponse);
        List<JsonNode> intelligence = hits(intelligenceResponse);
        List<JsonNode> relations = hits(relationsResponse);
        long contactCount = totalHits(contactsResponse, contacts.size());
        long intelligenceCount = totalHits(intelligenceResponse, intelligence.size());
        long relationCount = totalHits(relationsResponse, relations.size());

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("contactCount", Long.toString(contactCount));
        fields.put("publicIntelCount", Long.toString(intelligenceCount));
        fields.put("relationCount", Long.toString(relationCount));
        put(fields, "contactTypes", joinDistinct(contacts, "/contact_type", 10));
        put(fields, "maskedContacts", joinDistinct(contacts, "/masked_value", 5));
        put(fields, "officialWebsites", relationJson(contacts.stream()
            .map(hit -> hit.path("_source"))
            .filter(source -> isWebsiteContact(textAt(source, "/contact_type")))
            .map(source -> firstText(
                textAt(source, "/value"),
                textAt(source, "/masked_value")
            ))
            .filter(value -> value != null)
            .distinct()
            .map(value -> Map.of("value", value))
            .toList()));
        put(fields, "shareholders", relationJson(relationsOfType(relations, "SHAREHOLDER")));
        put(fields, "keyPersonnel", relationJson(relationsOfTypes(
            relations, List.of("MAIN_PERSON", "CORE_PERSON")
        )));
        put(fields, "outboundInvestments", relationJson(relationsOfType(
            relations, "OUTBOUND_INVESTMENT"
        )));
        put(fields, "branches", relationJson(relationsOfType(relations, "BRANCH")));
        put(fields, "latestPublicIntelAt", intelligence.stream()
            .map(hit -> firstText(
                textAt(hit.path("_source"), "/published_at"),
                textAt(hit.path("_source"), "/captured_at")
            ))
            .filter(value -> value != null)
            .findFirst()
            .orElse(null));
        put(fields, "latestPublicIntel", joinPublicIntel(intelligence));

        Instant contactDataAsOf = contacts.stream()
            .map(hit -> firstInstant(
                textAt(hit.path("_source"), "/source/source_updated_at"),
                textAt(hit.path("_source"), "/ingest/ingested_at")
            ))
            .reduce(null, ElasticsearchCompanyDataProvider::later);
        Instant intelligenceDataAsOf = intelligence.stream()
            .map(hit -> firstInstant(
                textAt(hit.path("_source"), "/source/source_updated_at"),
                textAt(hit.path("_source"), "/published_at"),
                textAt(hit.path("_source"), "/captured_at")
            ))
            .reduce(null, ElasticsearchCompanyDataProvider::later);
        Instant relationDataAsOf = relations.stream()
            .map(hit -> firstInstant(
                textAt(hit.path("_source"), "/source/source_updated_at"),
                textAt(hit.path("_source"), "/ingest/ingested_at")
            ))
            .reduce(null, ElasticsearchCompanyDataProvider::later);
        return new CompanyContext(fields, List.of(
            successStatus(
                properties.getContactAlias(),
                contactCount,
                contactDataAsOf,
                fetchedAt
            ),
            successStatus(
                properties.getPublicIntelAlias(),
                intelligenceCount,
                intelligenceDataAsOf,
                fetchedAt
            ),
            successStatus(
                properties.getRelationAlias(),
                relationCount,
                relationDataAsOf,
                fetchedAt
            )
        ));
    }

    private static boolean isWebsiteContact(String contactType) {
        return contactType != null && Set.of("WEBSITE", "网站", "网址")
            .contains(contactType.trim().toUpperCase());
    }

    private static List<Map<String, String>> relationsOfType(
        List<JsonNode> hits,
        String relationType
    ) {
        return relationsOfTypes(hits, List.of(relationType));
    }

    private static List<Map<String, String>> relationsOfTypes(
        List<JsonNode> hits,
        List<String> relationTypes
    ) {
        return hits.stream()
            .map(hit -> hit.path("_source"))
            .filter(source -> relationTypes.contains(textAt(source, "/relation_type")))
            .map(ElasticsearchCompanyDataProvider::compactRelation)
            .toList();
    }

    private static Map<String, String> compactRelation(JsonNode source) {
        Map<String, String> relation = new LinkedHashMap<>();
        put(relation, "type", textAt(source, "/relation_type"));
        put(relation, "name", textAt(source, "/subject/name"));
        put(relation, "entityType", textAt(source, "/subject/entity_type"));
        put(relation, "position", textAt(source, "/subject/position"));
        put(relation, "brief", textAt(source, "/subject/brief"));
        put(relation, "legalRepresentative", textAt(source, "/subject/legal_representative"));
        put(relation, "status", textAt(source, "/status"));
        put(relation, "ratio", firstText(
            textAt(source, "/ownership/ratio_raw"),
            textAt(source, "/ownership/ratio_percent")
        ));
        put(relation, "registeredAmount", firstText(
            textAt(source, "/ownership/registered_amount_raw"),
            textAt(source, "/ownership/registered_amount")
        ));
        put(relation, "paidAmount", firstText(
            textAt(source, "/ownership/paid_amount_raw"),
            textAt(source, "/ownership/paid_amount")
        ));
        put(relation, "capitalDate", textAt(source, "/ownership/capital_date"));
        put(relation, "validFrom", textAt(source, "/valid_from"));
        put(relation, "sourceTable", textAt(source, "/source/table"));
        put(relation, "sourceRecordId", textAt(source, "/source/record_id"));
        return relation;
    }

    private String relationJson(List<Map<String, String>> records) throws IOException {
        return records.isEmpty() ? null : objectMapper.writeValueAsString(records);
    }

    private static CompanyCandidate candidate(
        JsonNode source,
        CompanyQuery query,
        Instant fetchedAt
    ) {
        Map<String, String> attributes = companyAttributes(source);
        attributes.put("fetchedAt", fetchedAt.toString());
        return new CompanyCandidate(
            SOURCE_SYSTEM,
            textAt(source, "/atlas_company_id"),
            textAt(source, "/name/canonical"),
            textAt(source, "/identity/credit_code"),
            textAt(source, "/identity/register_no"),
            textAt(source, "/registration/legal_representative"),
            textAt(source, "/registration/status"),
            firstText(
                textAt(source, "/addresses/business"),
                textAt(source, "/addresses/registered"),
                textAt(source, "/addresses/report")
            ),
            query.looksLikeUnifiedCreditCode() ? BigDecimal.ONE : new BigDecimal("0.9800"),
            companyDataAsOf(source),
            attributes
        );
    }

    private static CompanyFacts facts(
        JsonNode source,
        Instant fetchedAt,
        Map<String, String> contextFields
    ) {
        String capital = join(
            textAt(source, "/capital/registered_raw"),
            textAt(source, "/capital/unit")
        );
        Map<String, String> additionalFields = companyAttributes(source);
        additionalFields.putAll(contextFields);
        return new CompanyFacts(
            textAt(source, "/name/canonical"),
            textAt(source, "/identity/credit_code"),
            textAt(source, "/identity/register_no"),
            textAt(source, "/registration/legal_representative"),
            textAt(source, "/registration/status"),
            firstText(
                textAt(source, "/addresses/business"),
                textAt(source, "/addresses/registered"),
                textAt(source, "/addresses/report")
            ),
            textAt(source, "/registration/company_type"),
            capital,
            textAt(source, "/registration/open_date"),
            textAt(source, "/registration/authority"),
            textAt(source, "/registration/business_scope"),
            firstText(
                textAt(source, "/industry/level1_name"),
                textAt(source, "/industry/source_value")
            ),
            SOURCE_SYSTEM,
            firstText(textAt(source, "/source/record_id"), textAt(source, "/atlas_company_id")),
            companyDataAsOf(source),
            fetchedAt,
            additionalFields
        );
    }

    private static CompanyChange change(JsonNode source, Instant fetchedAt) {
        return new CompanyChange(
            firstText(textAt(source, "/source/record_id"), textAt(source, "/event_id")),
            firstText(textAt(source, "/change_detail/item"), textAt(source, "/title")),
            firstText(textAt(source, "/event_date"), textAt(source, "/source/source_updated_at")),
            textAt(source, "/change_detail/before"),
            textAt(source, "/change_detail/after"),
            SOURCE_SYSTEM,
            eventDataAsOf(source),
            fetchedAt,
            eventFields(source)
        );
    }

    private static RiskEvent riskEvent(JsonNode source, Instant fetchedAt) {
        return new RiskEvent(
            firstText(textAt(source, "/risk/risk_type"), textAt(source, "/event_type"), "OTHER"),
            SOURCE_SYSTEM,
            firstText(textAt(source, "/source/table"), textAt(source, "/event_group")),
            firstText(textAt(source, "/source/record_id"), textAt(source, "/event_id")),
            firstText(
                textAt(source, "/event_date"),
                textAt(source, "/publish_date"),
                textAt(source, "/source/source_updated_at")
            ),
            firstText(textAt(source, "/title"), textAt(source, "/event_type")),
            firstText(textAt(source, "/summary"), textAt(source, "/title")),
            eventDataAsOf(source),
            fetchedAt,
            eventFields(source)
        );
    }

    private static Map<String, String> companyAttributes(JsonNode source) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "atlasCompanyId", textAt(source, "/atlas_company_id"));
        put(fields, "sourceCompanyId", textAt(source, "/identity/source_company_id"));
        put(fields, "shortName", textAt(source, "/name/short"));
        put(fields, "formerNames", textAt(source, "/name/aliases"));
        put(fields, "brands", textAt(source, "/name/brands"));
        put(fields, "storeNames", textAt(source, "/name/store_names"));
        put(fields, "actualController", textAt(source, "/control/actual_controller"));
        put(fields, "websites", textAt(source, "/business_profile/websites"));
        put(fields, "insuredNum", textAt(source, "/business_profile/insured_num"));
        put(fields, "businessEnd", textAt(source, "/registration/business_end"));
        String legacyScore = textAt(source, "/risk_projection/legacy_score");
        String legacyLabels = textAt(source, "/risk_projection/legacy_labels");
        put(fields, "riskScore", legacyScore);
        put(fields, "legacyScore", legacyScore);
        put(fields, "riskLabels", legacyLabels);
        put(fields, "legacyLabels", legacyLabels);
        put(fields, "industryId", textAt(source, "/industry/industry_id"));
        put(fields, "paidCapital", textAt(source, "/capital/paid_value"));
        put(fields, "listingInfo", textAt(source, "/extensions/listing_info"));
        put(fields, "monitorCompany", textAt(source, "/extensions/is_monitor"));
        put(fields, "legacyFeatureCompleteness", textAt(source, "/extensions/legacy_feature_completeness"));
        put(fields, "legacyScoringProfile", textAt(source, "/extensions/legacy_scoring_profile"));
        put(fields, "sourceTable", textAt(source, "/source/table"));
        return fields;
    }

    private static Map<String, String> eventFields(JsonNode source) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "eventGroup", textAt(source, "/event_group"));
        put(fields, "eventType", textAt(source, "/event_type"));
        put(fields, "status", textAt(source, "/status"));
        put(fields, "authority", textAt(source, "/authority"));
        put(fields, "documentNo", textAt(source, "/document_no"));
        put(fields, "riskType", textAt(source, "/risk/risk_type"));
        put(fields, "severity", textAt(source, "/risk/severity"));
        put(fields, "verificationStatus", textAt(source, "/risk/verification_status"));
        return fields;
    }

    private static Instant companyDataAsOf(JsonNode source) {
        return firstInstant(
            textAt(source, "/freshness/business_updated_at"),
            textAt(source, "/source/source_updated_at"),
            textAt(source, "/ingest/ingested_at")
        );
    }

    private static Instant eventDataAsOf(JsonNode source) {
        return firstInstant(
            textAt(source, "/source/source_updated_at"),
            textAt(source, "/event_date"),
            textAt(source, "/ingest/ingested_at")
        );
    }

    private static List<JsonNode> hits(JsonNode response) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode hits = response.path("hits").path("hits");
        if (hits.isArray()) {
            hits.forEach(result::add);
        }
        return result;
    }

    private static long totalHits(JsonNode response, int fallback) {
        JsonNode total = response.path("hits").path("total");
        if (total.isIntegralNumber()) {
            return total.asLong();
        }
        return total.path("value").asLong(fallback);
    }

    private static <T> SourceResult<T> result(
        List<T> records,
        String sourceName,
        Instant dataAsOf,
        Instant fetchedAt
    ) {
        return new SourceResult<>(
            records.isEmpty() ? QueryStatus.SUCCESS_EMPTY : QueryStatus.SUCCESS_WITH_RESULTS,
            records,
            List.of(successStatus(sourceName, records.size(), dataAsOf, fetchedAt))
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
            "ELASTICSEARCH_QUERY_FAILED",
            exception.getMessage()
        );
    }

    private static String textAt(JsonNode source, String pointer) {
        JsonNode value = source.at(pointer);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isValueNode()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        return value.toString();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Instant firstInstant(String... values) {
        for (String value : values) {
            Instant parsed = parseInstant(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignoredDate) {
                    return null;
                }
            }
        }
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private static String join(String first, String second) {
        return String.join(" ", first == null ? "" : first, second == null ? "" : second).trim();
    }

    private static String joinDistinct(List<JsonNode> hits, String pointer, int maximum) {
        return String.join(", ", hits.stream()
            .map(hit -> textAt(hit.path("_source"), pointer))
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(maximum)
            .toList());
    }

    private static String joinPublicIntel(List<JsonNode> hits) {
        return String.join(" | ", hits.stream()
            .map(hit -> {
                JsonNode source = hit.path("_source");
                return firstText(
                    textAt(source, "/title"),
                    textAt(source, "/summary"),
                    textAt(source, "/content")
                );
            })
            .filter(value -> value != null && !value.isBlank())
            .map(ElasticsearchCompanyDataProvider::abbreviate)
            .limit(3)
            .toList());
    }

    private static String abbreviate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    private record CompanyContext(
        Map<String, String> additionalFields,
        List<SourceStatus> sourceStatuses
    ) {
    }
}
