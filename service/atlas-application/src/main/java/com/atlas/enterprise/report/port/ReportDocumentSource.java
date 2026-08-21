package com.atlas.enterprise.report.port;

import com.atlas.enterprise.report.ReportDocument;
import java.util.UUID;

public interface ReportDocumentSource {
    ReportDocument loadTemplate();

    default ReportDocument loadTemplate(UUID taskId) {
        return loadTemplate();
    }

    ReportDocument loadPrevious(String reference);
}
