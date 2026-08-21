package com.atlas.enterprise.report;

import java.util.Arrays;
import java.util.Map;

public record ReportDocument(
    String reference,
    String templateVersion,
    String contentHash,
    byte[] content,
    Map<String, String> fieldMapping
) {
    public ReportDocument {
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        fieldMapping = fieldMapping == null ? Map.of() : Map.copyOf(fieldMapping);
    }

    public ReportDocument(
        String reference,
        String templateVersion,
        String contentHash,
        byte[] content
    ) {
        this(reference, templateVersion, contentHash, content, Map.of());
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
