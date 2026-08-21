package com.atlas.enterprise.report.port;

import com.atlas.enterprise.report.ReportDocument;
import com.atlas.enterprise.report.ReportGenerationData;

public interface ReportTemplateRenderer {
    String rendererVersion();

    byte[] render(ReportDocument template, ReportGenerationData data);
}
