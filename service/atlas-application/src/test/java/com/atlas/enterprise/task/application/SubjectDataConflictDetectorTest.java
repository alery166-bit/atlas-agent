package com.atlas.enterprise.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.CompanyFacts;
import com.atlas.enterprise.company.DataSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectDataConflictDetectorTest {
    @Test
    void detectsLatestLegalRepresentativeChangeThatDiffersFromMaster() {
        DataSnapshot snapshot = snapshot("任志远", List.of(
            change("法定代表人变更", "任志远", "陆小安", "2024-07-04")
        ));

        var conflicts = SubjectDataConflictDetector.detect(snapshot);

        assertEquals(1, conflicts.size());
        assertEquals("LEGAL_REPRESENTATIVE_CONFLICT", conflicts.get(0).code());
        assertEquals("任志远", conflicts.get(0).masterValue());
        assertEquals("陆小安", conflicts.get(0).latestChangeValue());
    }

    @Test
    void ignoresRepresentativeChangeWhenMasterAlreadyMatches() {
        assertTrue(SubjectDataConflictDetector.detect(snapshot("陆小安", List.of(
            change("法定代表人变更", "任志远", "陆小安", "2024-07-04")
        ))).isEmpty());
    }

    @Test
    void ignoresHistoricalChangeWhenCurrentMasterIsNewer() {
        Instant masterAsOf = Instant.parse("2026-08-19T00:00:00Z");
        Instant changeAsOf = Instant.parse("2024-07-04T00:00:00Z");
        DataSnapshot snapshot = snapshot(
            "徐俊达",
            masterAsOf,
            List.of(change(
                "法定代表人变更", "曹国章", "秦树东", "2020-07-10", changeAsOf
            ))
        );

        assertTrue(SubjectDataConflictDetector.detect(snapshot).isEmpty());
    }

    private static DataSnapshot snapshot(
        String representative,
        List<CompanyChange> changes
    ) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        return snapshot(representative, now, changes);
    }

    private static DataSnapshot snapshot(
        String representative,
        Instant masterAsOf,
        List<CompanyChange> changes
    ) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        return new DataSnapshot(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            new CompanyFacts(
                "示例企业有限公司", "91110000TEST000001", null,
                representative, "存续", "北京市", "有限责任公司",
                "1000万元", "2020-01-01", "市场监管局", "技术服务",
                "信息技术", "fixture", "company-1", masterAsOf, now, Map.of()
            ),
            changes,
            List.of(),
            List.of(),
            "snapshot-hash",
            now
        );
    }

    private static CompanyChange change(
        String item,
        String before,
        String after,
        String changedAt
    ) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        return change(item, before, after, changedAt, now);
    }

    private static CompanyChange change(
        String item,
        String before,
        String after,
        String changedAt,
        Instant dataAsOf
    ) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        return new CompanyChange(
            "change-1", item, changedAt, before, after, "fixture",
            dataAsOf, now, Map.of()
        );
    }
}
