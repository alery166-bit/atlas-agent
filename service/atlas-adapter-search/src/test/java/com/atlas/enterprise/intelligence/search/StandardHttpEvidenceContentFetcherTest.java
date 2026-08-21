package com.atlas.enterprise.intelligence.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.intelligence.EvidenceContentCapture;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandardHttpEvidenceContentFetcherTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void capturesHtmlWithHashesAndExtractedText() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/evidence", exchange -> respond(exchange, """
            <html><head><style>.hidden {display:none}</style></head>
            <body><h1>目标企业闭店</h1>
            <script>window.secret = true;</script>
            <p>目标企业有限公司相关门店已经关闭。</p></body></html>
            """));
        server.start();
        EvidenceContentProperties properties = new EvidenceContentProperties();
        properties.setAllowPrivateNetwork(true);

        EvidenceContentCapture capture =
            new StandardHttpEvidenceContentFetcher(properties).fetch(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/evidence"
            );

        assertEquals(EvidenceContentStatus.CAPTURED, capture.status());
        assertTrue(capture.extractedText().contains("目标企业有限公司"));
        assertFalse(capture.extractedText().contains("window.secret"));
        assertEquals(64, capture.rawContentHash().length());
        assertEquals(64, capture.extractedTextHash().length());
    }

    @Test
    void blocksPrivateNetworkByDefault() {
        EvidenceContentCapture capture =
            new StandardHttpEvidenceContentFetcher(
                new EvidenceContentProperties()
            ).fetch("http://127.0.0.1/internal");

        assertEquals(EvidenceContentStatus.FAILED, capture.status());
        assertEquals("PRIVATE_NETWORK_BLOCKED", capture.failureCode());
    }

    private static void respond(HttpExchange exchange, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type",
            "text/html; charset=UTF-8"
        );
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
