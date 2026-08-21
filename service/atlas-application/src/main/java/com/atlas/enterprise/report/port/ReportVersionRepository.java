package com.atlas.enterprise.report.port;

import com.atlas.enterprise.report.ReportVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ReportVersionRepository {
    ReportVersion save(ReportVersion report);

    Optional<ReportVersion> findById(UUID reportId);

    Optional<ReportVersion> findByInputHash(UUID taskId, String inputHash);

    List<ReportVersion> findByTaskId(UUID taskId);

    Map<UUID, ReportVersion> findLatestByTaskIds(List<UUID> taskIds);

    int nextVersion(UUID taskId);
}
