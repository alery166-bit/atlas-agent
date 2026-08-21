package com.atlas.enterprise.report.port;

import com.atlas.enterprise.report.ReportDocument;
import java.util.List;
import java.util.Map;

public interface ManagedReportTemplateStore {
    StoredTemplate save(String originalFilename, byte[] content, String operatorId);

    ReportDocument load(String artifactId, String templateVersion, String expectedHash);

    Inspection inspect(byte[] content);

    boolean containsText(byte[] content, String text);

    record StoredTemplate(
        String artifactId,
        String originalFilename,
        String contentHash,
        long byteLength,
        Inspection inspection
    ) {}

    record Inspection(
        boolean valid,
        int paragraphCount,
        int tableCount,
        List<String> detectedMarkers,
        List<String> missingMarkers,
        Map<String, String> suggestedMapping,
        String message
    ) {}
}
