package com.atlas.enterprise.report.port;

import com.atlas.enterprise.report.StoredReportObject;
import java.util.UUID;

public interface ReportStorage {
    StoredReportObject put(UUID reportId, byte[] content);

    byte[] get(String uri);

    boolean exists(String uri, String contentHash);
}
