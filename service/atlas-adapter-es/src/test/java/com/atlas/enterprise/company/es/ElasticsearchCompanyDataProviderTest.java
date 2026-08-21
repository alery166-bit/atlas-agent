package com.atlas.enterprise.company.es;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.CompanyQuery;
import com.atlas.enterprise.company.CompanyResolutionStatus;
import com.atlas.enterprise.company.QueryStatus;
import com.atlas.enterprise.company.ResolvedCompany;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ElasticsearchCompanyDataProviderTest {
    private static final String COMPANY_ID = "094dd2ba-0a43-5c6a-8402-f3e4b20dc86e";
    private static final Instant FETCHED_AT = Instant.parse("2026-08-03T08:00:00Z");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesCompanyAndLoadsFactsChangesAndRiskEventsWithRouting() throws Exception {
        List<String> eventQueries = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/atlas-company-read/_search", exchange ->
            respond(exchange, 200, companyResponse())
        );
        server.createContext("/atlas-company-event-read/_search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            eventQueries.add(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, body.contains("REGISTRATION_CHANGE")
                ? changeResponse()
                : riskResponse());
        });
        server.createContext("/atlas-company-contact-read/_search", exchange ->
            respond(exchange, 200, contactResponse())
        );
        server.createContext("/atlas-public-intel-read/_search", exchange ->
            respond(exchange, 200, publicIntelResponse())
        );
        server.createContext("/atlas-company-relation-read/_search", exchange ->
            respond(exchange, 200, relationResponse())
        );
        server.start();

        ElasticsearchCompanyDataProvider provider = provider();
        var resolution = provider.resolve(new CompanyQuery("北京简熹和食品有限公司"));

        assertEquals(CompanyResolutionStatus.UNIQUE, resolution.status());
        assertEquals(SOURCE(), resolution.uniqueCandidate().sourceSystem());
        assertEquals(COMPANY_ID, resolution.uniqueCandidate().sourceEntityId());
        assertEquals("91110113MAK5DEJQ0W", resolution.uniqueCandidate().unifiedCreditCode());

        ResolvedCompany company = resolution.uniqueCandidate().resolve();
        var facts = provider.loadFacts(company);
        var changes = provider.loadChanges(company);
        var risks = provider.loadRiskEvents(company);

        assertEquals(QueryStatus.SUCCESS_WITH_RESULTS, facts.queryStatus());
        assertEquals("葛雪", facts.records().getFirst().legalRepresentative());
        assertEquals("1 万元", facts.records().getFirst().registeredCapital());
        assertEquals("ELASTICSEARCH", facts.records().getFirst().sourceSystem());
        assertEquals("1", facts.records().getFirst().additionalFields().get("contactCount"));
        assertEquals("1", facts.records().getFirst().additionalFields().get("publicIntelCount"));
        assertEquals("网站", facts.records().getFirst().additionalFields().get("contactTypes"));
        assertEquals("3.25", facts.records().getFirst().additionalFields().get("riskScore"));
        assertEquals("3.25", facts.records().getFirst().additionalFields().get("legacyScore"));
        assertEquals("[\"103112113\"]", facts.records().getFirst().additionalFields().get("riskLabels"));
        assertEquals("1", facts.records().getFirst().additionalFields().get("relationCount"));
        assertTrue(facts.records().getFirst().additionalFields().get("officialWebsites")
            .contains("https://www.example.test"));
        assertTrue(facts.records().getFirst().additionalFields().get("shareholders")
            .contains("测试股东有限公司"));
        assertEquals(4, facts.sourceStatuses().size());
        assertEquals(1, changes.records().size());
        assertEquals("股东信息变更", changes.records().getFirst().changeItem());
        assertEquals(1, risks.records().size());
        assertEquals("WAGE_ARREARS", risks.records().getFirst().eventType());
        assertEquals(2, eventQueries.size());
        assertTrue(eventQueries.stream().allMatch(query -> query.equals("routing=" + COMPANY_ID)));
    }

    @Test
    void returnsNotFoundForEmptyCompanyResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/atlas-company-read/_search", exchange ->
            respond(exchange, 200, emptyResponse())
        );
        server.start();

        var result = provider().resolve(new CompanyQuery("不存在企业有限公司"));

        assertEquals(CompanyResolutionStatus.NOT_FOUND, result.status());
        assertTrue(result.candidates().isEmpty());
        assertFalse(result.sourceStatuses().getFirst().failed());
    }

    @Test
    void mapsUpstreamFailureToFailedSourceResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/atlas-company-read/_search", exchange ->
            respond(exchange, 503, "{\"error\":\"unavailable\"}")
        );
        server.start();

        var result = provider().resolve(new CompanyQuery("北京简熹和食品有限公司"));

        assertEquals(CompanyResolutionStatus.FAILED, result.status());
        assertTrue(result.sourceStatuses().getFirst().failed());
        assertEquals("ELASTICSEARCH_QUERY_FAILED", result.sourceStatuses().getFirst().errorCode());
    }

    private ElasticsearchCompanyDataProvider provider() {
        ElasticsearchDataProperties properties = new ElasticsearchDataProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.validate();
        return new ElasticsearchCompanyDataProvider(
            properties,
            new ObjectMapper(),
            Clock.fixed(FETCHED_AT, ZoneOffset.UTC)
        );
    }

    private static String SOURCE() {
        return ElasticsearchCompanyDataProvider.SOURCE_SYSTEM;
    }

    private static String companyResponse() {
        return """
            {
              "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{
                  "_id": "%s",
                  "_source": {
                    "atlas_company_id": "%s",
                    "identity": {
                      "source_company_id": "q1c2cc690e31c11f0978b00163e0ee983",
                      "credit_code": "91110113MAK5DEJQ0W",
                      "register_no": "110113046525829"
                    },
                    "name": {"canonical": "北京简熹和食品有限公司"},
                    "registration": {
                      "company_type": "有限责任公司",
                      "status": "存续",
                      "legal_representative": "葛雪",
                      "authority": "北京市朝阳区市场监督管理局",
                      "open_date": "2025-12-26",
                      "business_scope": "食品销售"
                    },
                    "capital": {"registered_raw": "1", "unit": "万元"},
                    "addresses": {"registered": "北京市朝阳区测试地址"},
                    "industry": {"level1_name": "零售业"},
                    "control": {"actual_controller": "葛雪"},
                    "business_profile": {"insured_num": 0},
                    "risk_projection": {
                      "legacy_score": 3.25,
                      "legacy_labels": ["103112113"]
                    },
                    "freshness": {"business_updated_at": "2026-07-28T15:45:52+08:00"},
                    "source": {"table": "company_base", "record_id": "1108178"},
                    "ingest": {"ingested_at": "2026-08-03T00:00:00Z"}
                  }
                }]
              }
            }
            """.formatted(COMPANY_ID, COMPANY_ID);
    }

    private static String changeResponse() {
        return """
            {
              "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{"_source": {
                  "event_id": "change-1",
                  "atlas_company_id": "%s",
                  "event_group": "REGISTRATION",
                  "event_type": "REGISTRATION_CHANGE",
                  "event_date": "2026-07-01",
                  "title": "股东信息变更",
                  "change_detail": {"item": "股东信息变更", "before": "甲", "after": "乙"},
                  "source": {"table": "company_change", "record_id": "change-1", "source_updated_at": "2026-07-02T00:00:00Z"}
                }}]
              }
            }
            """.formatted(COMPANY_ID);
    }

    private static String riskResponse() {
        return """
            {
              "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{"_source": {
                  "event_id": "risk-1",
                  "atlas_company_id": "%s",
                  "event_group": "LABOR",
                  "event_type": "LABOR_COMPLAINT",
                  "risk_relevant": true,
                  "event_date": "2026-07-15",
                  "title": "员工反映欠薪",
                  "summary": "待运营核验",
                  "risk": {"risk_type": "WAGE_ARREARS", "verification_status": "SOURCE_RECORD"},
                  "source": {"table": "labor_complaint", "record_id": "risk-1", "source_updated_at": "2026-07-16T00:00:00Z"}
                }}]
              }
            }
            """.formatted(COMPANY_ID);
    }

    private static String contactResponse() {
        return """
            {
              "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{"_source": {
                  "contact_id": "contact-1",
                  "atlas_company_id": "%s",
                  "contact_type": "网站",
                  "value": "https://www.example.test",
                  "masked_value": "https://www.example.test",
                  "source": {"source_updated_at": "2026-07-20T00:00:00Z"}
                }}]
              }
            }
            """.formatted(COMPANY_ID);
    }

    private static String relationResponse() {
        return """
            {
              "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{"_source": {
                  "relation_id": "relation-1",
                  "atlas_company_id": "%s",
                  "relation_type": "SHAREHOLDER",
                  "direction": "INBOUND",
                  "subject": {"name": "测试股东有限公司", "entity_type": "企业法人"},
                  "ownership": {"ratio_raw": "40%%", "registered_amount_raw": "2000"},
                  "source": {
                    "table": "company_shareholder", "record_id": "shareholder-1",
                    "source_updated_at": "2026-07-20T00:00:00Z"
                  }
                }}]
              }
            }
            """.formatted(COMPANY_ID);
    }

    private static String publicIntelResponse() {
        return """
            {
              "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{"_source": {
                  "intel_id": "intel-1",
                  "atlas_company_id": "%s",
                  "title": "企业公开信息",
                  "published_at": "2026-07-18",
                  "source": {"source_updated_at": "2026-07-19T00:00:00Z"}
                }}]
              }
            }
            """.formatted(COMPANY_ID);
    }

    private static String emptyResponse() {
        return "{\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
