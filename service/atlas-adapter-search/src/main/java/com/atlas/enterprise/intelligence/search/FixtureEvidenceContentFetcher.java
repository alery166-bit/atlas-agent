package com.atlas.enterprise.intelligence.search;

import com.atlas.enterprise.intelligence.EvidenceContentCapture;
import com.atlas.enterprise.intelligence.EvidenceContentStatus;
import com.atlas.enterprise.intelligence.port.EvidenceContentFetcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class FixtureEvidenceContentFetcher implements EvidenceContentFetcher {
    @Override
    public EvidenceContentCapture fetch(String url) {
        String raw = """
            <html><body><main>
            <h1>样本云门店关闭信息</h1>
            <p>公开页面显示样本云相关门店已经闭店，待人工核验。</p>
            </main></body></html>
            """;
        String text = HtmlTextExtractor.extract(raw);
        return new EvidenceContentCapture(
            EvidenceContentStatus.CAPTURED,
            url,
            url,
            200,
            "text/html; charset=UTF-8",
            raw,
            text,
            hash(raw),
            hash(text),
            raw.getBytes(StandardCharsets.UTF_8).length,
            false,
            null,
            null,
            Instant.parse("2026-07-30T02:00:00Z")
        );
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
