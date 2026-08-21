package com.atlas.enterprise.task.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.risk.RiskScoreSnapshot;
import com.atlas.enterprise.risk.RiskType;
import com.atlas.enterprise.task.OperatorConfirmation;
import com.atlas.enterprise.task.SubjectDataConflictResolution;
import com.atlas.enterprise.task.TaskStep;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record TaskWorkspaceView(
    TaskView task,
    DataSnapshot dataSnapshot,
    List<SubjectDataConflict> subjectDataConflicts,
    SubjectDataConflictResolution subjectDataConflictResolution,
    RiskScoreSnapshot riskScore,
    PreviousReportScore previousReportScore,
    EvidenceProgress evidenceProgress,
    OperatorConfirmation latestConfirmation,
    ConfirmationState confirmationState,
    boolean confirmationReady,
    boolean reportGenerationReady,
    List<ReadinessBlocker> readinessBlockers,
    NextAction nextAction,
    List<ReportVersion> reports,
    List<TaskStep> steps
) {
    public TaskWorkspaceView {
        subjectDataConflicts = List.copyOf(subjectDataConflicts);
        readinessBlockers = List.copyOf(readinessBlockers);
        reports = List.copyOf(reports);
        steps = List.copyOf(steps);
    }

    public record SubjectDataConflict(
        String code,
        String fieldName,
        String masterValue,
        String latestChangeValue,
        String changedAt,
        String sourceSystem,
        String sourceRecordId
    ) {
    }

    public record PreviousReportScore(
        String sourceFileId,
        ParseStatus status,
        BigDecimal originalScore,
        BigDecimal manualScore,
        String riskLevel,
        LocalDate reportDate,
        double confidence
    ) {
    }

    public enum ParseStatus {
        AVAILABLE,
        MISSING_SCORE,
        UNAVAILABLE
    }

    public record EvidenceProgress(
        int total,
        int confirmed,
        int rejected,
        int unverified,
        int capturedContent,
        int failedContentCapture,
        Map<RiskType, Integer> byRiskType
    ) {
        public EvidenceProgress {
            byRiskType = Map.copyOf(byRiskType);
        }
    }

    public enum ConfirmationState {
        NOT_READY,
        PENDING,
        VALID,
        STALE
    }

    public enum ReadinessBlocker {
        DATA_SNAPSHOT_MISSING,
        RISK_SCORE_MISSING,
        RISK_SCORE_NOT_FROM_LATEST_DATA,
        UNVERIFIED_EVIDENCE,
        SUBJECT_DATA_CONFLICT
    }

    public enum NextAction {
        EXECUTE_TASK,
        CONFIRM_SUBJECT,
        REVIEW_EVIDENCE,
        REVIEW_SUBJECT_DATA,
        CALCULATE_RISK,
        CONFIRM_REVIEW,
        GENERATE_REPORT,
        RETRY_TASK,
        RETRY_REPORT,
        WAIT,
        DOWNLOAD_REPORT,
        NONE
    }
}
