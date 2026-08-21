package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.CompanyChange;
import com.atlas.enterprise.company.DataSnapshot;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SubjectDataConflictDetector {
    private SubjectDataConflictDetector() {
    }

    public static List<TaskWorkspaceView.SubjectDataConflict> detect(
        DataSnapshot snapshot
    ) {
        if (snapshot == null) {
            return List.of();
        }
        CompanyChange representativeChange = snapshot.companyChanges().stream()
            .filter(SubjectDataConflictDetector::isRepresentativeChange)
            .filter(change -> hasText(change.afterValue()))
            .max(Comparator.comparing(
                CompanyChange::changedAt,
                Comparator.nullsFirst(String::compareTo)
            ))
            .orElse(null);
        String masterRepresentative = snapshot.companyFacts()
            .legalRepresentative();
        if (representativeChange == null
            || masterSupersedesChange(snapshot, representativeChange)
            || equivalent(
                masterRepresentative,
                representativeChange.afterValue()
            )) {
            return List.of();
        }
        return List.of(new TaskWorkspaceView.SubjectDataConflict(
            "LEGAL_REPRESENTATIVE_CONFLICT",
            "法定代表人",
            display(masterRepresentative),
            display(representativeChange.afterValue()),
            representativeChange.changedAt(),
            representativeChange.sourceSystem(),
            representativeChange.sourceRecordId()
        ));
    }

    private static boolean masterSupersedesChange(
        DataSnapshot snapshot,
        CompanyChange representativeChange
    ) {
        return snapshot.companyFacts().dataAsOf() != null
            && representativeChange.dataAsOf() != null
            && snapshot.companyFacts().dataAsOf()
                .isAfter(representativeChange.dataAsOf());
    }

    private static boolean isRepresentativeChange(CompanyChange change) {
        String item = normalize(change.changeItem());
        return item.contains("法定代表")
            || item.equals("负责人")
            || item.contains("负责人变更");
    }

    private static boolean equivalent(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\\"'“”‘’（）()]", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String display(String value) {
        return hasText(value) ? value.trim() : "/";
    }
}
