package com.atlas.enterprise.company.refresh;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.QueryStatus;
import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.RiskEvent;
import com.atlas.enterprise.company.SourceResult;
import com.atlas.enterprise.company.SourceStatus;
import com.atlas.enterprise.company.port.CompanyRefreshPort;
import com.atlas.enterprise.company.port.CompanyRefreshResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class XlbCompanyRefreshAdapter implements CompanyRefreshPort {
    static final String PROVIDER = "xlb-openapi";

    private final XlbCompanyRefreshProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final XlbApiClient client;

    XlbCompanyRefreshAdapter(
        XlbCompanyRefreshProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.client = new XlbApiClient(properties, objectMapper);
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public CompanyRefreshResult refresh(ResolvedCompany company) {
        return refresh(company, () -> { });
    }

    @Override
    public CompanyRefreshResult refresh(ResolvedCompany company, Runnable heartbeat) {
        Instant fetchedAt = clock.instant();
        String refreshId = UUID.randomUUID().toString();
        if (!enabled()) {
            throw new IllegalStateException("XLB company refresh is disabled");
        }

        Map<XlbRefreshCategory, CategoryFetch> fetched = new LinkedHashMap<>();
        String eid;
        try {
            heartbeat.run();
            eid = resolveEid(company);
            heartbeat.run();
        } catch (IOException exception) {
            SourceStatus failure = failedStatus("RESOLVE", fetchedAt, exception);
            return failedResult(refreshId, fetchedAt, List.of(failure));
        }

        Set<XlbRefreshCategory> categories = new LinkedHashSet<>(properties.requiredCategorySet());
        categories.addAll(properties.optionalCategorySet());
        for (XlbRefreshCategory category : categories) {
            heartbeat.run();
            fetched.put(category, fetch(category, eid, fetchedAt));
            heartbeat.run();
        }

        List<SourceStatus> statuses = fetched.values().stream()
            .map(CategoryFetch::status)
            .toList();
        List<SourceStatus> requiredStatuses = properties.requiredCategorySet().stream()
            .map(fetched::get)
            .filter(Objects::nonNull)
            .map(CategoryFetch::status)
            .toList();
        boolean requiredFailure = properties.requiredCategorySet().stream()
            .map(fetched::get)
            .filter(Objects::nonNull)
            .anyMatch(result -> result.status().failed());
        if (requiredFailure) {
            return failedResult(refreshId, fetchedAt, statuses);
        }

        JsonNode base = first(fetched, XlbRefreshCategory.BASE);
        String canonicalName = text(base, "entname");
        String creditCode = text(base, "creditCode");
        if (!sameSubject(company, canonicalName, creditCode)) {
            List<SourceStatus> mismatchStatuses = new ArrayList<>(statuses);
            mismatchStatuses.add(new SourceStatus(
                PROVIDER,
                "xlb#SUBJECT_VALIDATION",
                QueryStatus.FAILED,
                0,
                fetchedAt,
                fetchedAt,
                "COMPANY_REFRESH_SUBJECT_MISMATCH",
                "Refreshed company identity does not match the confirmed Atlas subject"
            ));
            return failedResult(refreshId, fetchedAt, mismatchStatuses);
        }

        CompanyFacts facts = companyFacts(eid, base, fetched, fetchedAt, refreshId);
        List<CompanyChange> changes = changes(fetched, fetchedAt);
        List<RiskEvent> riskEvents = riskEvents(fetched, fetchedAt);
        return new CompanyRefreshResult(
            refreshId,
            PROVIDER,
            fetchedAt,
            sourceResult(List.of(facts), requiredStatuses),
            sourceResult(changes, requiredStatuses),
            sourceResult(riskEvents, requiredStatuses),
            statuses
        );
    }

    private String resolveEid(ResolvedCompany company) throws IOException {
        String query = firstText(company.unifiedCreditCode(), company.canonicalName());
        JsonNode base = client.object(
            4713,
            Map.of("uscc", query),
            "search.queryCompanyByUscc.base"
        );
        String eid = text(base, "eid");
        if (eid == null || eid.isBlank()) {
            throw new IOException("XLB subject resolution returned no eid");
        }
        return eid;
    }

    private CategoryFetch fetch(XlbRefreshCategory category, String eid, Instant fetchedAt) {
        try {
            List<JsonNode> records = switch (category) {
                case BASE -> List.of(client.object(1001, Map.of("eid", eid), "entInfo.base"));
                case CONTACT -> contacts(eid);
                case SHAREHOLDER -> page(4819, eid, "entInfo.pagerEntSharehold");
                case INVESTMENT -> page(1061, eid, "entInfo.pagerInvestments");
                case BRANCH -> page(1021, eid, "entInfo.pagerBranches");
                case CHANGE -> page(1031, eid, "entInfo.pagerAlterRecord");
                case MAIN_PERSON -> page(1101, eid, "entInfo.pagerPriPersons");
                case TAX_CREDIT -> page(4767, eid, "entInfo.pagerTaxpayerASwj");
                case FINANCING -> financing(eid);
                case BUSINESS -> page(1591, eid, "entInfo.pagerProjects");
                case CERTIFICATE -> page(3080, eid, "entInfo.entZzzs");
                case TRADEMARK -> page(3001, eid, "entInfo.pagerTrademarks");
                case PATENT -> page(3021, eid, "entInfo.pagerParent");
                case COPYRIGHT -> page(3041, eid, "entInfo.pagerSoftwareCopyright");
                case DISHONEST -> page(2561, eid, "entInfo.pagerShixins");
                case EXECUTOR -> page(2591, eid, "entInfo.pagerZhixins");
                case LIMIT -> page(2651, eid, "yunyangData.pagerXzxf");
                case JUDGEMENT -> client.rolling(
                    4716, Map.of("eid", eid), "entInfo.scrollJudgmentDocument", "searchAfter", "after"
                );
                case EQUITY_FREEZE -> page(2531, eid, "entInfo.pagerEquityFreezes");
                case EQUITY_PLEDGE -> page(4828, eid, "entInfo.pagerStockGqzy");
                case EQUITY_HOSTAGE -> page(2541, eid, "entInfo.pagerEquityPledges");
                case FILING -> client.rolling(
                    4829, Map.of("eid", eid), "entInfo.searchAfterCaseAccept", "nextId", "afterId"
                );
                case ABNORMAL -> page(2501, eid, "entInfo.pagerBusinessAbnormals");
                case ILLEGAL -> page(2571, eid, "entInfo.pagerIlldisdetails");
                case ADMINISTRATIVE -> page(2581, eid, "entInfo.pagerCasePub");
                case ENVIRONMENT -> page(4770, eid, "entInfo.pagerHbPunish");
                case CANCELLATION -> page(4772, eid, "entInfo.pagerJyzx");
                case LIQUIDATION -> liquidation(eid);
                case TAX_ILLEGAL -> page(2611, eid, "yunyangData.pagerSswf");
                case AUCTION -> page(2731, eid, "yunyangData.pagerSfpm");
                case BANKRUPTCY -> page(2721, eid, "yunyangData.pagerPccz");
            };
            QueryStatus status = records.isEmpty()
                ? QueryStatus.SUCCESS_EMPTY
                : QueryStatus.SUCCESS_WITH_RESULTS;
            return new CategoryFetch(
                records,
                new SourceStatus(
                    PROVIDER,
                    "xlb#" + category.name(),
                    status,
                    records.size(),
                    fetchedAt,
                    fetchedAt,
                    null,
                    null
                )
            );
        } catch (IOException | RuntimeException exception) {
            return new CategoryFetch(
                List.of(),
                failedStatus(category.name(), fetchedAt, exception)
            );
        }
    }

    private List<JsonNode> page(int apiId, String eid, String path) throws IOException {
        return client.page(apiId, Map.of("eid", eid), path);
    }

    private List<JsonNode> contacts(String eid) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        addContact(result, page(2031, eid, "entInfo.pagerWebSites"), "WEBSITE");
        addContact(result, page(2041, eid, "entInfo.pagerContact"), "PHONE");
        JsonNode homePage = client.object(4530, Map.of("eid", eid), "entInfo.homePage");
        JsonNode emails = homePage.path("emailAgg");
        if (!emails.isMissingNode() && !emails.isNull()) {
            if (!emails.isArray()) {
                throw new IOException("XLB emailAgg is not an array");
            }
            addContact(result, toList(emails), "EMAIL");
        }
        JsonNode addressHome = client.object(2000, Map.of("eid", eid), "entInfo.homePage");
        JsonNode addresses = addressHome.path("addrAgg");
        if (!addresses.isMissingNode() && !addresses.isNull()) {
            if (!addresses.isArray()) {
                throw new IOException("XLB addrAgg is not an array");
            }
            addContact(result, toList(addresses), "ADDRESS");
        }
        return List.copyOf(result);
    }

    private List<JsonNode> financing(String eid) throws IOException {
        JsonNode homePage = client.object(1550, Map.of("eid", eid), "entInfo.homePage");
        JsonNode finances = homePage.path("apizzaInvests");
        if (finances.isMissingNode() || finances.isNull()) {
            return List.of();
        }
        if (!finances.isArray()) {
            throw new IOException("XLB apizzaInvests is not an array");
        }
        return toList(finances);
    }

    private List<JsonNode> liquidation(String eid) throws IOException {
        JsonNode value = client.nullableObject(2691, Map.of("eid", eid), "entInfo.liquidation");
        return value == null || value.isEmpty() ? List.of() : List.of(value);
    }

    private void addContact(List<JsonNode> target, List<JsonNode> source, String type) {
        for (JsonNode item : source) {
            ObjectNode copy = item.deepCopy();
            copy.put("_atlasContactType", type);
            target.add(copy);
        }
    }

    private CompanyFacts companyFacts(
        String eid,
        JsonNode base,
        Map<XlbRefreshCategory, CategoryFetch> fetched,
        Instant fetchedAt,
        String refreshId
    ) {
        Map<String, String> additional = new LinkedHashMap<>();
        put(additional, "shortName", text(base, "shortName"));
        put(additional, "insuredNum", text(base, "ssNum"));
        put(additional, "businessEnd", text(base, "opto"));
        put(additional, "paidCapital", money(base, "acconam", "regCapCurName"));
        put(additional, "refreshId", refreshId);

        List<JsonNode> contacts = records(fetched, XlbRefreshCategory.CONTACT);
        additional.put("contactCount", Integer.toString(contacts.size()));
        put(additional, "officialWebsites", json(contactMaps(contacts, "WEBSITE")));
        put(additional, "shareholders", json(shareholderMaps(records(fetched, XlbRefreshCategory.SHAREHOLDER))));
        put(additional, "keyPersonnel", json(personMaps(records(fetched, XlbRefreshCategory.MAIN_PERSON))));
        put(additional, "outboundInvestments", json(investmentMaps(records(fetched, XlbRefreshCategory.INVESTMENT))));
        put(additional, "branches", json(branchMaps(records(fetched, XlbRefreshCategory.BRANCH))));
        put(additional, "taxCredits", json(records(fetched, XlbRefreshCategory.TAX_CREDIT)));
        put(additional, "financing", json(records(fetched, XlbRefreshCategory.FINANCING)));
        put(additional, "businessProjects", json(records(fetched, XlbRefreshCategory.BUSINESS)));
        put(additional, "certificates", json(records(fetched, XlbRefreshCategory.CERTIFICATE)));
        put(additional, "trademarks", json(records(fetched, XlbRefreshCategory.TRADEMARK)));
        put(additional, "patents", json(records(fetched, XlbRefreshCategory.PATENT)));
        put(additional, "copyrights", json(records(fetched, XlbRefreshCategory.COPYRIGHT)));

        return new CompanyFacts(
            text(base, "entname"),
            text(base, "creditCode"),
            text(base, "regno"),
            text(base, "frname"),
            text(base, "entStatusNameBi"),
            text(base, "dom"),
            text(base, "enttypeName"),
            money(base, "regcap", "regCapCurName"),
            text(base, "esdate"),
            text(base, "regorgName"),
            text(base, "opscope"),
            firstText(text(base, "industryphy2Name"), text(base, "industryphy1Name"), text(base, "industryphyName")),
            PROVIDER,
            eid,
            fetchedAt,
            fetchedAt,
            additional
        );
    }

    private List<CompanyChange> changes(
        Map<XlbRefreshCategory, CategoryFetch> fetched,
        Instant fetchedAt
    ) {
        return records(fetched, XlbRefreshCategory.CHANGE).stream()
            .map(source -> new CompanyChange(
                stableId(XlbRefreshCategory.CHANGE, source),
                text(source, "altItemName"),
                text(source, "altdate"),
                text(source, "altbe"),
                text(source, "altaf"),
                PROVIDER,
                fetchedAt,
                fetchedAt,
                rawFields(source)
            ))
            .toList();
    }

    private List<RiskEvent> riskEvents(
        Map<XlbRefreshCategory, CategoryFetch> fetched,
        Instant fetchedAt
    ) {
        List<RiskEvent> events = new ArrayList<>();
        for (XlbRefreshCategory category : properties.requiredCategorySet()) {
            if (!riskCategory(category)) {
                continue;
            }
            for (JsonNode source : records(fetched, category)) {
                events.add(riskEvent(category, source, fetchedAt));
            }
        }
        return List.copyOf(events);
    }

    private RiskEvent riskEvent(XlbRefreshCategory category, JsonNode source, Instant fetchedAt) {
        String eventType = eventType(category);
        String occurredAt = eventDate(category, source);
        String title = eventTitle(category, source, eventType);
        String summary = eventSummary(category, source, title);
        Map<String, String> raw = rawFields(source);
        raw.put("eventType", eventType);
        raw.put("riskType", eventType);
        put(raw, "status", firstText(text(source, "frozstateCn"), text(source, "unfreezeState"), text(source, "exestate")));
        put(raw, "authority", firstText(text(source, "courtName"), text(source, "penauthCn"), text(source, "punisher")));
        put(raw, "documentNo", firstText(text(source, "caseCode"), text(source, "caseNo"), text(source, "pendecno"), text(source, "punishPaper")));
        return new RiskEvent(
            eventType,
            PROVIDER,
            "xlb#" + category.name(),
            stableId(category, source),
            occurredAt,
            title,
            summary,
            fetchedAt,
            fetchedAt,
            raw
        );
    }

    private static boolean riskCategory(XlbRefreshCategory category) {
        return switch (category) {
            case DISHONEST, EXECUTOR, LIMIT, JUDGEMENT, EQUITY_FREEZE, EQUITY_PLEDGE,
                EQUITY_HOSTAGE, FILING, ABNORMAL, ILLEGAL, ADMINISTRATIVE, ENVIRONMENT,
                CANCELLATION, LIQUIDATION, TAX_ILLEGAL, AUCTION, BANKRUPTCY -> true;
            default -> false;
        };
    }

    private static String eventType(XlbRefreshCategory category) {
        return switch (category) {
            case DISHONEST -> "DISHONEST";
            case EXECUTOR -> "ENFORCEMENT";
            case LIMIT -> "LIMIT_CONSUMPTION";
            case JUDGEMENT -> "JUDGEMENT";
            case EQUITY_FREEZE -> "EQUITY_FREEZE";
            case EQUITY_PLEDGE -> "EQUITY_PLEDGE";
            case EQUITY_HOSTAGE -> "EQUITY_HOSTAGE";
            case FILING -> "CASE_FILING";
            case ABNORMAL -> "BUSINESS_ABNORMAL";
            case ILLEGAL -> "SERIOUS_ILLEGAL";
            case ADMINISTRATIVE -> "ADMINISTRATIVE_PENALTY";
            case ENVIRONMENT -> "ENVIRONMENTAL_PENALTY";
            case CANCELLATION -> "SIMPLE_CANCELLATION";
            case LIQUIDATION -> "LIQUIDATION";
            case TAX_ILLEGAL -> "TAX_ILLEGAL";
            case AUCTION -> "JUDICIAL_AUCTION";
            case BANKRUPTCY -> "BANKRUPTCY";
            default -> throw new IllegalArgumentException("Not a risk category: " + category);
        };
    }

    private static String eventDate(XlbRefreshCategory category, JsonNode source) {
        return switch (category) {
            case DISHONEST -> firstText(text(source, "regDate"), text(source, "publishDate"));
            case EXECUTOR, LIMIT -> text(source, "regDate");
            case JUDGEMENT -> text(source, "publishDate");
            case EQUITY_FREEZE -> firstText(text(source, "frofrom"), text(source, "publicdate"));
            case EQUITY_PLEDGE -> text(source, "noticeDate");
            case EQUITY_HOSTAGE -> firstText(text(source, "equpledate"), text(source, "impPubdate"));
            case FILING -> text(source, "regDate");
            case ABNORMAL -> text(source, "dateIn");
            case ILLEGAL -> text(source, "abntime");
            case ADMINISTRATIVE -> text(source, "pendecissdate");
            case ENVIRONMENT -> text(source, "punishTime");
            case CANCELLATION -> firstText(text(source, "apprdate"), text(source, "cancelDate"), text(source, "canceldate"));
            case LIQUIDATION -> text(source, "esdate");
            case TAX_ILLEGAL -> text(source, "pushTime");
            case AUCTION -> text(source, "pubtime");
            case BANKRUPTCY -> text(source, "pubdate");
            default -> null;
        };
    }

    private static String eventTitle(XlbRefreshCategory category, JsonNode source, String fallback) {
        return switch (category) {
            case JUDGEMENT, AUCTION -> firstText(text(source, "title"), fallback);
            case FILING -> firstText(text(source, "caseNo"), fallback);
            case ADMINISTRATIVE -> firstText(text(source, "pendecno"), fallback);
            case ENVIRONMENT -> firstText(text(source, "punishPaper"), fallback);
            case BANKRUPTCY -> firstText(text(source, "caseNumber"), fallback);
            default -> fallback;
        };
    }

    private static String eventSummary(XlbRefreshCategory category, JsonNode source, String fallback) {
        return switch (category) {
            case DISHONEST, EXECUTOR -> firstText(text(source, "caseCode"), fallback);
            case LIMIT -> firstText(text(source, "entName"), text(source, "name"), fallback);
            case JUDGEMENT -> firstText(text(source, "reasons"), text(source, "title"), fallback);
            case EQUITY_FREEZE -> firstText(text(source, "inv"), text(source, "executeno"), fallback);
            case EQUITY_PLEDGE -> firstText(text(source, "holderName"), text(source, "pfOrg"), fallback);
            case EQUITY_HOSTAGE -> firstText(text(source, "impPledgor"), text(source, "impOrg"), fallback);
            case FILING -> firstText(text(source, "reason"), text(source, "caseNo"), fallback);
            case ABNORMAL -> firstText(text(source, "resultIn"), fallback);
            case ILLEGAL -> firstText(text(source, "serillreaCn"), fallback);
            case ADMINISTRATIVE -> firstText(text(source, "pencontent"), text(source, "punishmentReason"), fallback);
            case ENVIRONMENT -> firstText(text(source, "punishResult"), text(source, "unlawfulAct"), fallback);
            case CANCELLATION -> firstText(text(source, "cancelResult"), fallback);
            case LIQUIDATION -> firstText(text(source, "cancaelrea"), fallback);
            case TAX_ILLEGAL -> firstText(text(source, "illegalFact"), text(source, "punishments"), fallback);
            case AUCTION -> firstText(text(source, "auctionName"), text(source, "title"), fallback);
            case BANKRUPTCY -> firstText(text(source, "respondentRaw"), text(source, "proposer"), fallback);
            default -> fallback;
        };
    }

    private List<Map<String, String>> contactMaps(List<JsonNode> records, String type) {
        return records.stream()
            .filter(item -> type.equals(text(item, "_atlasContactType")))
            .map(item -> {
                Map<String, String> value = new LinkedHashMap<>();
                put(value, "value", firstText(text(item, "openUrl"), text(item, "value")));
                put(value, "name", firstText(text(item, "name"), text(item, "remark")));
                return value;
            })
            .filter(item -> !item.isEmpty())
            .toList();
    }

    private List<Map<String, String>> shareholderMaps(List<JsonNode> records) {
        return records.stream().map(item -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("type", "SHAREHOLDER");
            put(value, "name", text(item, "invName"));
            put(value, "entityType", text(item, "invType"));
            put(value, "registeredAmount", text(item, "subConam"));
            put(value, "paidAmount", text(item, "acConam"));
            put(value, "ratio", text(item, "shareHoldRate"));
            put(value, "capitalDate", text(item, "conDate"));
            return value;
        }).toList();
    }

    private List<Map<String, String>> personMaps(List<JsonNode> records) {
        return records.stream().map(item -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("type", text(item.at("/personInfo/basicProfile/brief")) == null ? "MAIN_PERSON" : "CORE_PERSON");
            put(value, "name", text(item, "name"));
            put(value, "position", text(item, "positionName"));
            put(value, "brief", text(item.at("/personInfo/basicProfile/brief")));
            return value;
        }).toList();
    }

    private List<Map<String, String>> investmentMaps(List<JsonNode> records) {
        return records.stream().map(item -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("type", "OUTBOUND_INVESTMENT");
            put(value, "name", text(item, "name"));
            put(value, "legalRepresentative", text(item, "frname"));
            put(value, "status", text(item, "entstatusName"));
            put(value, "ratio", text(item, "fundedRatioNum"));
            put(value, "registeredAmount", text(item, "subConam"));
            put(value, "validFrom", text(item, "esdate"));
            return value;
        }).toList();
    }

    private List<Map<String, String>> branchMaps(List<JsonNode> records) {
        return records.stream().map(item -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("type", "BRANCH");
            put(value, "name", text(item, "name"));
            put(value, "legalRepresentative", text(item, "frname"));
            put(value, "status", text(item, "entstatusName"));
            return value;
        }).toList();
    }

    private String json(Object value) {
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize refreshed company context", exception);
        }
    }

    private static Map<String, String> rawFields(JsonNode source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode()) {
                result.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return result;
    }

    private static String stableId(XlbRefreshCategory category, JsonNode source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(PROVIDER.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(category.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(source.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean sameSubject(
        ResolvedCompany expected,
        String actualName,
        String actualCreditCode
    ) {
        if (expected.unifiedCreditCode() != null && !expected.unifiedCreditCode().isBlank()
            && actualCreditCode != null && !actualCreditCode.isBlank()) {
            return expected.unifiedCreditCode().trim().equalsIgnoreCase(actualCreditCode.trim());
        }
        return normalizeName(expected.canonicalName()).equals(normalizeName(actualName));
    }

    private static String normalizeName(String value) {
        return value == null
            ? ""
            : value.trim().replace('（', '(').replace('）', ')').replaceAll("\\s+", "");
    }

    private static String money(JsonNode source, String amountField, String currencyField) {
        String amount = text(source, amountField);
        if (amount == null) {
            return null;
        }
        try {
            amount = new BigDecimal(amount).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            // Preserve the provider's raw value when it is not a plain decimal.
        }
        String currency = text(source, currencyField);
        return currency == null || currency.isBlank() ? amount : amount + "万" + currency;
    }

    private static List<JsonNode> records(
        Map<XlbRefreshCategory, CategoryFetch> fetched,
        XlbRefreshCategory category
    ) {
        CategoryFetch result = fetched.get(category);
        return result == null ? List.of() : result.records();
    }

    private static JsonNode first(
        Map<XlbRefreshCategory, CategoryFetch> fetched,
        XlbRefreshCategory category
    ) {
        List<JsonNode> records = records(fetched, category);
        if (records.isEmpty()) {
            throw new IllegalStateException("Required XLB category returned no record: " + category);
        }
        return records.getFirst();
    }

    private static <T> SourceResult<T> sourceResult(List<T> records, List<SourceStatus> statuses) {
        QueryStatus status = records.isEmpty()
            ? QueryStatus.SUCCESS_EMPTY
            : QueryStatus.SUCCESS_WITH_RESULTS;
        return new SourceResult<>(status, records, statuses);
    }

    private static CompanyRefreshResult failedResult(
        String refreshId,
        Instant fetchedAt,
        List<SourceStatus> statuses
    ) {
        SourceResult<CompanyFacts> facts = new SourceResult<>(QueryStatus.FAILED, List.of(), statuses);
        SourceResult<CompanyChange> changes = new SourceResult<>(QueryStatus.FAILED, List.of(), statuses);
        SourceResult<RiskEvent> events = new SourceResult<>(QueryStatus.FAILED, List.of(), statuses);
        return new CompanyRefreshResult(refreshId, PROVIDER, fetchedAt, facts, changes, events, statuses);
    }

    private static SourceStatus failedStatus(String category, Instant fetchedAt, Exception exception) {
        return new SourceStatus(
            PROVIDER,
            "xlb#" + category,
            QueryStatus.FAILED,
            0,
            fetchedAt,
            fetchedAt,
            "XLB_" + category + "_FAILED",
            safeMessage(exception)
        );
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        return node == null ? null : text(node.path(field));
    }

    private static String text(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private record CategoryFetch(List<JsonNode> records, SourceStatus status) {
        private CategoryFetch {
            records = List.copyOf(records);
        }
    }
}
