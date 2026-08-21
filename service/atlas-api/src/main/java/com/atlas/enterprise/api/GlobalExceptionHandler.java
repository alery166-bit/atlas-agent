package com.atlas.enterprise.api;

import com.atlas.enterprise.agent.application.AgentMessageValidationException;
import com.atlas.enterprise.agent.application.AgentConversationNotFoundException;
import com.atlas.enterprise.agent.application.AgentTaskAccessDeniedException;
import com.atlas.enterprise.task.InvalidTaskTransitionException;
import com.atlas.enterprise.task.TaskErrorCode;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.application.TaskListValidationException;
import com.atlas.enterprise.task.application.TaskWorkflowConflictException;
import com.atlas.enterprise.task.application.OperatorConfirmationValidationException;
import com.atlas.enterprise.company.application.SnapshotNotFoundException;
import com.atlas.enterprise.intelligence.application.EvidenceNotFoundException;
import com.atlas.enterprise.intelligence.application.PublicIntelligenceValidationException;
import com.atlas.enterprise.intelligence.application.RequiredSearchProviderFailedException;
import com.atlas.enterprise.risk.application.RiskScoreNotFoundException;
import com.atlas.enterprise.risk.application.RiskScoreValidationException;
import com.atlas.enterprise.report.application.ReportNotFoundException;
import com.atlas.enterprise.report.application.PreviousReportUploadException;
import com.atlas.enterprise.report.application.ReportValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.atlas.enterprise.configuration.application.ConfigurationConflictException;
import com.atlas.enterprise.configuration.application.SkillDisabledException;
import com.atlas.enterprise.configuration.application.ConfigurationNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        GlobalExceptionHandler.class
    );

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ErrorResponse> taskNotFound(TaskNotFoundException exception, HttpServletRequest request) {
        return response(
            HttpStatus.NOT_FOUND,
            "TASK_NOT_FOUND",
            exception.getMessage(),
            exception.taskId(),
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    ResponseEntity<ErrorResponse> snapshotNotFound(
        SnapshotNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.NOT_FOUND,
            "DATA_SNAPSHOT_NOT_FOUND",
            exception.getMessage(),
            exception.taskId(),
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(RiskScoreNotFoundException.class)
    ResponseEntity<ErrorResponse> riskScoreNotFound(
        RiskScoreNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.NOT_FOUND,
            "RISK_SCORE_NOT_FOUND",
            exception.getMessage(),
            null,
            false,
            Map.of("target_id", exception.targetId()),
            request
        );
    }

    @ExceptionHandler(RiskScoreValidationException.class)
    ResponseEntity<ErrorResponse> invalidRiskScore(
        RiskScoreValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_RISK_SCORE_REQUEST",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(EvidenceNotFoundException.class)
    ResponseEntity<ErrorResponse> evidenceNotFound(
        EvidenceNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.NOT_FOUND,
            "EVIDENCE_NOT_FOUND",
            exception.getMessage(),
            null,
            false,
            Map.of("evidence_id", exception.evidenceId()),
            request
        );
    }

    @ExceptionHandler(PublicIntelligenceValidationException.class)
    ResponseEntity<ErrorResponse> invalidPublicIntelligence(
        PublicIntelligenceValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_PUBLIC_INTELLIGENCE_REQUEST",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(RequiredSearchProviderFailedException.class)
    ResponseEntity<ErrorResponse> searchProviderFailed(
        RequiredSearchProviderFailedException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.SERVICE_UNAVAILABLE,
            TaskErrorCode.SEARCH_PROVIDER_UNAVAILABLE.name(),
            exception.getMessage(),
            null,
            true,
            Map.of(
                "provider", exception.provider(),
                "failure_code", exception.failureCode()
            ),
            request
        );
    }

    @ExceptionHandler(ReportNotFoundException.class)
    ResponseEntity<ErrorResponse> reportNotFound(
        ReportNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.NOT_FOUND,
            "REPORT_NOT_FOUND",
            exception.getMessage(),
            null,
            false,
            Map.of("report_id", exception.reportId()),
            request
        );
    }

    @ExceptionHandler(ReportValidationException.class)
    ResponseEntity<ErrorResponse> invalidReport(
        ReportValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "REPORT_GENERATION_INVALID",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(TaskWorkflowConflictException.class)
    ResponseEntity<ErrorResponse> workflowConflict(
        TaskWorkflowConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.CONFLICT,
            TaskErrorCode.TASK_STATE_CONFLICT.name(),
            exception.getMessage(),
            exception.taskId(),
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(OperatorConfirmationValidationException.class)
    ResponseEntity<ErrorResponse> invalidOperatorConfirmation(
        OperatorConfirmationValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "OPERATOR_CONFIRMATION_INVALID",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(InvalidTaskTransitionException.class)
    ResponseEntity<ErrorResponse> invalidTransition(
        InvalidTaskTransitionException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.CONFLICT,
            TaskErrorCode.TASK_STATE_CONFLICT.name(),
            exception.getMessage(),
            null,
            false,
            Map.of("from", exception.from().name(), "to", exception.to().name()),
            request
        );
    }

    @ExceptionHandler(TaskListValidationException.class)
    ResponseEntity<ErrorResponse> invalidTaskList(
        TaskListValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_TASK_LIST_QUERY",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(AgentMessageValidationException.class)
    ResponseEntity<ErrorResponse> invalidAgentMessage(
        AgentMessageValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_AGENT_MESSAGE",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(PreviousReportUploadException.class)
    ResponseEntity<ErrorResponse> invalidPreviousReportUpload(
        PreviousReportUploadException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_PREVIOUS_REPORT_UPLOAD",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(AgentConversationNotFoundException.class)
    ResponseEntity<ErrorResponse> agentConversationNotFound(
        AgentConversationNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.NOT_FOUND,
            "AGENT_CONVERSATION_NOT_FOUND",
            exception.getMessage(),
            null,
            false,
            Map.of("conversation_id", exception.conversationId()),
            request
        );
    }

    @ExceptionHandler(AgentTaskAccessDeniedException.class)
    ResponseEntity<ErrorResponse> agentTaskAccessDenied(
        AgentTaskAccessDeniedException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.FORBIDDEN,
            "AGENT_TASK_ACCESS_DENIED",
            exception.getMessage(),
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException validation) {
            validation.getBindingResult().getFieldErrors()
                .forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        }
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "Request validation failed",
            null,
            false,
            details,
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
            "Unhandled API exception traceId={} method={} uri={}",
            request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
            request.getMethod(),
            request.getRequestURI(),
            exception
        );
        return response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Unexpected server error",
            null,
            false,
            Map.of(),
            request
        );
    }

    @ExceptionHandler(ConfigurationNotFoundException.class)
    ResponseEntity<ErrorResponse> configurationNotFound(
        ConfigurationNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "CONFIGURATION_NOT_FOUND",
            exception.getMessage(), null, false, Map.of(), request);
    }

    @ExceptionHandler(ConfigurationConflictException.class)
    ResponseEntity<ErrorResponse> configurationConflict(
        ConfigurationConflictException exception,
        HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, "CONFIGURATION_CONFLICT",
            exception.getMessage(), null, true, Map.of(), request);
    }

    @ExceptionHandler(SkillDisabledException.class)
    ResponseEntity<ErrorResponse> skillDisabled(
        SkillDisabledException exception,
        HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, "SKILL_DISABLED",
            exception.getMessage(), null, false, Map.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> illegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
            exception.getMessage(), null, false, Map.of(), request);
    }

    private ResponseEntity<ErrorResponse> response(
        HttpStatus status,
        String code,
        String message,
        java.util.UUID taskId,
        boolean retryable,
        Map<String, Object> details,
        HttpServletRequest request
    ) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        return ResponseEntity.status(status).body(new ErrorResponse(
            code,
            message,
            taskId,
            traceId,
            retryable,
            details,
            Instant.now()
        ));
    }
}
