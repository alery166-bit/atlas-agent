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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class CsvCompanyDataProvider implements CompanyDataProvider {
    static final String SOURCE_SYSTEM = "OFFLINE_CSV";
    private static final String COMPANY_BASE = "company_base.csv";
    private static final String COMPANY_CHANGE = "company_change.csv";
    private static final TypeReference<LinkedHashMap<String, String>> ROW_TYPE = new TypeReference<>() {};
    private static final CsvSchema HEADER_SCHEMA = CsvSchema.emptySchema().withHeader();
    private static final List<RiskSource> RISK_SOURCES = List.of(
        new RiskSource("company_abnormal.csv", "BUSINESS_ABNORMAL", "经营异常", "enter_date",
            List.of("enter_reason", "enter_office", "out_date", "out_reason")),
        new RiskSource("company_administrative_penalty.csv", "ADMINISTRATIVE_PENALTY", "行政处罚", "penalty_date",
            List.of("document_no", "decide_office", "penalty_content", "penalty_fact")),
        new RiskSource("company_dishonest.csv", "DISHONEST", "失信被执行", "publish_date",
            List.of("case_no", "court", "case_date")),
        new RiskSource("company_executor.csv", "JUDGMENT_DEBTOR", "被执行", "case_date",
            List.of("case_no", "court", "target_execution")),
        new RiskSource("company_limit_consumption.csv", "LIMIT_CONSUMPTION", "限制高消费", "case_date",
            List.of("case_no", "court", "target", "relation")),
        new RiskSource("company_illegal.csv", "SERIOUS_ILLEGAL", "严重违法", "enter_date",
            List.of("enter_reason", "enter_office", "out_date", "out_reason")),
        new RiskSource("company_tax_illegal.csv", "TAX_ILLEGAL", "税收违法", "publish_date",
            List.of("illegal_type", "illegal_content", "penalty_decision")),
        new RiskSource("company_environmental_penalty.csv", "ENVIRONMENTAL_PENALTY", "环保处罚", "penalty_date",
            List.of("document_no", "decide_office", "penalty_content", "penalty_fact")),
        new RiskSource("company_bankruptcy.csv", "BANKRUPTCY", "破产案件", "publish_date",
            List.of("case_no", "case_type", "court", "respondent", "proposer_raw"))
    );

    private final OfflineResourceAccess resources;
    private final Clock clock;
    private final CsvMapper csvMapper;

    public CsvCompanyDataProvider(OfflineResourceAccess resources, Clock clock) {
        this.resources = resources;
        this.clock = clock;
        this.csvMapper = CsvMapper.builder().build();
    }

    @Override
    public String providerName() {
        return SOURCE_SYSTEM;
    }

    @Override
    public CompanyResolution resolve(CompanyQuery query) {
        Instant fetchedAt = clock.instant();
        try {
            List<Map<String, String>> rows = matchingRows(
                COMPANY_BASE,
                row -> matchesQuery(row, query)
            );
            List<CompanyCandidate> candidates = rows.stream()
                .map(row -> candidate(row, query, fetchedAt))
                .toList();
            SourceStatus sourceStatus = successStatus(
                COMPANY_BASE,
                candidates.size(),
                maxDataAsOf(rows),
                fetchedAt
            );
            CompanyResolutionStatus status = candidates.isEmpty()
                ? CompanyResolutionStatus.NOT_FOUND
                : candidates.size() == 1
                    ? CompanyResolutionStatus.UNIQUE
                    : CompanyResolutionStatus.AMBIGUOUS;
            return new CompanyResolution(status, candidates, List.of(sourceStatus));
        } catch (IOException exception) {
            return new CompanyResolution(
                CompanyResolutionStatus.FAILED,
                List.of(),
                List.of(failedStatus(COMPANY_BASE, fetchedAt, exception))
            );
        }
    }

    @Override
    public SourceResult<CompanyFacts> loadFacts(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            List<Map<String, String>> rows = matchingRows(
                COMPANY_BASE,
                row -> company.sourceEntityId().equals(row.get("company_id"))
            );
            List<CompanyFacts> facts = rows.stream()
                .limit(1)
                .map(row -> facts(row, fetchedAt))
                .toList();
            return result(
                facts,
                List.of(successStatus(COMPANY_BASE, facts.size(), maxDataAsOf(rows), fetchedAt))
            );
        } catch (IOException exception) {
            return failedResult(COMPANY_BASE, fetchedAt, exception);
        }
    }

    @Override
    public SourceResult<CompanyChange> loadChanges(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        try {
            List<Map<String, String>> rows = matchingRows(
                COMPANY_CHANGE,
                row -> company.sourceEntityId().equals(row.get("company_id"))
            );
            List<CompanyChange> changes = rows.stream()
                .map(row -> new CompanyChange(
                    row.get("id"),
                    row.get("change_item"),
                    row.get("change_date"),
                    row.get("change_before"),
                    row.get("change_after"),
                    SOURCE_SYSTEM,
                    dataAsOf(row),
                    fetchedAt,
                    row
                ))
                .toList();
            return result(
                changes,
                List.of(successStatus(COMPANY_CHANGE, changes.size(), maxDataAsOf(rows), fetchedAt))
            );
        } catch (IOException exception) {
            return failedResult(COMPANY_CHANGE, fetchedAt, exception);
        }
    }

    @Override
    public SourceResult<RiskEvent> loadRiskEvents(ResolvedCompany company) {
        Instant fetchedAt = clock.instant();
        List<RiskEvent> events = new ArrayList<>();
        List<SourceStatus> statuses = new ArrayList<>();
        for (RiskSource source : RISK_SOURCES) {
            try {
                List<Map<String, String>> rows = matchingRows(
                    source.fileName(),
                    row -> company.sourceEntityId().equals(row.get("company_id"))
                );
                for (Map<String, String> row : rows) {
                    events.add(toRiskEvent(source, row, fetchedAt));
                }
                statuses.add(successStatus(
                    source.fileName(),
                    rows.size(),
                    maxDataAsOf(rows),
                    fetchedAt
                ));
            } catch (IOException exception) {
                statuses.add(failedStatus(source.fileName(), fetchedAt, exception));
            }
        }
        return result(events, statuses);
    }

    private List<Map<String, String>> matchingRows(
        String fileName,
        Predicate<Map<String, String>> predicate
    ) throws IOException {
        Resource resource = resources.csv(fileName);
        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("Required offline source is not readable: " + resource.getDescription());
        }
        List<Map<String, String>> matches = new ArrayList<>();
        try (
            InputStream input = resource.getInputStream();
            Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            MappingIterator<LinkedHashMap<String, String>> rows = csvMapper
                .readerFor(ROW_TYPE)
                .with(HEADER_SCHEMA)
                .readValues(reader)
        ) {
            while (rows.hasNextValue()) {
                Map<String, String> row = rows.nextValue();
                if (predicate.test(row)) {
                    matches.add(Map.copyOf(row));
                }
            }
        }
        return matches;
    }

    private boolean matchesQuery(Map<String, String> row, CompanyQuery query) {
        String candidate = query.looksLikeUnifiedCreditCode()
            ? row.get("credit_code")
            : row.get("company_name");
        return candidate != null && candidate.trim().equalsIgnoreCase(query.value());
    }

    private CompanyCandidate candidate(
        Map<String, String> row,
        CompanyQuery query,
        Instant fetchedAt
    ) {
        return new CompanyCandidate(
            SOURCE_SYSTEM,
            row.get("company_id"),
            row.get("company_name"),
            row.get("credit_code"),
            row.get("register_no"),
            row.get("legal_personal"),
            row.get("registration_status"),
            firstText(row.get("business_address"), row.get("address"), row.get("report_address")),
            query.looksLikeUnifiedCreditCode() ? BigDecimal.ONE : new BigDecimal("0.9800"),
            dataAsOf(row),
            Map.of(
                "company_type", value(row.get("company_type")),
                "industry_v8", value(row.get("industry_v8"))
            )
        );
    }

    private CompanyFacts facts(Map<String, String> row, Instant fetchedAt) {
        return new CompanyFacts(
            row.get("company_name"),
            row.get("credit_code"),
            row.get("register_no"),
            row.get("legal_personal"),
            row.get("registration_status"),
            firstText(row.get("business_address"), row.get("address"), row.get("report_address")),
            row.get("company_type"),
            joinCapital(row),
            row.get("open_date"),
            row.get("registration_authority"),
            row.get("business_scope"),
            row.get("industry_v8"),
            SOURCE_SYSTEM,
            row.get("company_id"),
            dataAsOf(row),
            fetchedAt,
            row
        );
    }

    private RiskEvent toRiskEvent(
        RiskSource source,
        Map<String, String> row,
        Instant fetchedAt
    ) {
        return new RiskEvent(
            source.eventType(),
            SOURCE_SYSTEM,
            source.fileName(),
            row.get("id"),
            firstText(row.get(source.occurredAtField()), row.get("update_time")),
            source.title(),
            summary(row, source.summaryFields()),
            dataAsOf(row),
            fetchedAt,
            row
        );
    }

    private static String summary(Map<String, String> row, List<String> fields) {
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            String fieldValue = row.get(field);
            if (fieldValue != null && !fieldValue.isBlank()) {
                parts.add(field + "=" + fieldValue.trim());
            }
        }
        return String.join("；", parts);
    }

    private static String joinCapital(Map<String, String> row) {
        return String.join(
            " ",
            List.of(value(row.get("register_capital")), value(row.get("unit")))
        ).trim();
    }

    private static Instant dataAsOf(Map<String, String> row) {
        return OfflineTimeParser.later(
            OfflineTimeParser.parse(row.get("update_time")),
            OfflineTimeParser.parse(row.get("check_date"))
        );
    }

    private static Instant maxDataAsOf(List<Map<String, String>> rows) {
        Instant result = null;
        for (Map<String, String> row : rows) {
            result = OfflineTimeParser.later(result, dataAsOf(row));
        }
        return result;
    }

    private static <T> SourceResult<T> result(List<T> records, List<SourceStatus> statuses) {
        QueryStatus queryStatus = statuses.stream().anyMatch(SourceStatus::failed)
            ? QueryStatus.FAILED
            : records.isEmpty() ? QueryStatus.SUCCESS_EMPTY : QueryStatus.SUCCESS_WITH_RESULTS;
        return new SourceResult<>(queryStatus, records, statuses);
    }

    private static <T> SourceResult<T> failedResult(
        String sourceName,
        Instant fetchedAt,
        IOException exception
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
            "STRUCTURED_SOURCE_QUERY_FAILED",
            exception.getMessage()
        );
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

    private record RiskSource(
        String fileName,
        String eventType,
        String title,
        String occurredAtField,
        List<String> summaryFields
    ) {
    }
}
