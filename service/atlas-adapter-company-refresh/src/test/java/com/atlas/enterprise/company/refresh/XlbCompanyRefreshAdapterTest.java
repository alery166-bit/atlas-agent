package com.atlas.enterprise.company.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.ResolvedCompany;
import com.atlas.enterprise.company.port.CompanyRefreshResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class XlbCompanyRefreshAdapterTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsRequiredCompanyChangeAndRiskData() throws Exception {
        startServer(Map.of(
            "4713", success("""
                {"search":{"queryCompanyByUscc":{"base":{"eid":"E-100"}}}}
                """),
            "1001", success("""
                {"entInfo":{"base":{"eid":"E-100","entname":"示例科技有限公司",
                "creditCode":"91110000123456789X","regno":"110000012345678",
                "frname":"张三","entStatusNameBi":"存续","dom":"北京市海淀区",
                "enttypeName":"有限责任公司","regcap":"1000","regCapCurName":"人民币",
                "esdate":"2020-01-02","regorgName":"北京市市场监督管理局",
                "opscope":"技术服务","industryphy2Name":"软件和信息技术服务业"}}}
                """),
            "1031", success(page("""
                [{"altItemName":"法定代表人变更","altdate":"2025-03-04",
                "altbe":"李四","altaf":"张三"}]
                """)),
            "2501", success(page("""
                [{"dateIn":"2025-04-05","resultIn":"通过登记住所无法联系",
                "decisionOrgIn":"北京市市场监督管理局"}]
                """))
        ));

        XlbCompanyRefreshProperties properties = properties(
            List.of("BASE", "CHANGE", "ABNORMAL"),
            List.of()
        );
        AtomicInteger heartbeats = new AtomicInteger();
        CompanyRefreshResult result = adapter(properties).refresh(
            company(),
            heartbeats::incrementAndGet
        );

        assertFalse(result.failed());
        assertEquals("示例科技有限公司", result.facts().records().getFirst().canonicalName());
        assertEquals(1, result.changes().records().size());
        assertEquals("BUSINESS_ABNORMAL", result.riskEvents().records().getFirst().eventType());
        assertEquals(3, result.categoryStatuses().size());
        assertEquals(8, heartbeats.get());
    }

    @Test
    void requiredCategoryFailureStopsRefresh() throws Exception {
        startServer(Map.of(
            "4713", success("""
                {"search":{"queryCompanyByUscc":{"base":{"eid":"E-100"}}}}
                """),
            "1001", success("""
                {"entInfo":{"base":{"eid":"E-100","entname":"示例科技有限公司",
                "creditCode":"91110000123456789X"}}}
                """),
            "2501", new Response(503, "upstream unavailable")
        ));

        XlbCompanyRefreshProperties properties = properties(
            List.of("BASE", "ABNORMAL"),
            List.of()
        );
        CompanyRefreshResult result = adapter(properties).refresh(company());

        assertTrue(result.failed());
        assertTrue(result.firstFailureMessage().contains("HTTP status 503"));
    }

    @Test
    void optionalCategoryFailureDoesNotTurnRequiredRefreshIntoFailure() throws Exception {
        startServer(Map.of(
            "4713", success("""
                {"search":{"queryCompanyByUscc":{"base":{"eid":"E-100"}}}}
                """),
            "1001", success("""
                {"entInfo":{"base":{"eid":"E-100","entname":"示例科技有限公司",
                "creditCode":"91110000123456789X"}}}
                """),
            "3001", new Response(503, "upstream unavailable")
        ));

        XlbCompanyRefreshProperties properties = properties(
            List.of("BASE"),
            List.of("TRADEMARK")
        );
        CompanyRefreshResult result = adapter(properties).refresh(company());

        assertFalse(result.failed());
        assertTrue(result.categoryStatuses().stream().anyMatch(status -> status.failed()));
    }

    @Test
    void nullLiquidationIsAnExplicitSuccessfulEmptyResult() throws Exception {
        startServer(Map.of(
            "4713", success("""
                {"search":{"queryCompanyByUscc":{"base":{"eid":"E-100"}}}}
                """),
            "1001", success("""
                {"entInfo":{"base":{"eid":"E-100","entname":"示例科技有限公司",
                "creditCode":"91110000123456789X"}}}
                """),
            "2691", success("""
                {"entInfo":{"liquidation":null}}
                """)
        ));

        XlbCompanyRefreshProperties properties = properties(
            List.of("BASE", "LIQUIDATION"),
            List.of()
        );
        CompanyRefreshResult result = adapter(properties).refresh(company());

        assertFalse(result.failed());
        assertTrue(result.riskEvents().records().isEmpty());
        assertEquals(
            "SUCCESS_EMPTY",
            result.categoryStatuses().stream()
                .filter(status -> status.sourceName().endsWith("#LIQUIDATION"))
                .findFirst()
                .orElseThrow()
                .queryStatus()
                .name()
        );
    }

    private XlbCompanyRefreshAdapter adapter(XlbCompanyRefreshProperties properties) {
        return new XlbCompanyRefreshAdapter(
            properties,
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-08-21T03:00:00Z"), ZoneOffset.UTC)
        );
    }

    private XlbCompanyRefreshProperties properties(
        List<String> required,
        List<String> optional
    ) {
        XlbCompanyRefreshProperties properties = new XlbCompanyRefreshProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        properties.setAccessId("test-access-id");
        properties.setAccessToken("test-access-token");
        properties.setMaxAttempts(1);
        properties.setRequiredCategories(required);
        properties.setOptionalCategories(optional);
        properties.validate();
        return properties;
    }

    private static ResolvedCompany company() {
        return new ResolvedCompany(
            "atlas-es",
            "atlas-company-100",
            "示例科技有限公司",
            "91110000123456789X",
            "110000012345678"
        );
    }

    private void startServer(Map<String, Response> responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, responses));
        server.start();
    }

    private static void respond(HttpExchange exchange, Map<String, Response> responses)
        throws IOException {
        String apiId = exchange.getRequestURI().getPath().substring(1);
        Response response = responses.getOrDefault(apiId, new Response(404, "missing test fixture"));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Response success(String body) {
        return new Response(200, body);
    }

    private static String page(String records) {
        return "{\"entInfo\":{\"pagerAlterRecord\":{\"data\":" + records
            + ",\"totalCount\":1},\"pagerBusinessAbnormals\":{\"data\":" + records
            + ",\"totalCount\":1}}}";
    }

    private record Response(int status, String body) {
    }
}
