package com.atlas.enterprise.agent.application;

import com.atlas.enterprise.report.ReportStatus;
import com.atlas.enterprise.report.ReportVersion;
import com.atlas.enterprise.agent.port.AgentConversationRepository;
import com.atlas.enterprise.task.TaskStatus;
import com.atlas.enterprise.task.application.CreateTaskCommand;
import com.atlas.enterprise.task.application.TaskApplicationService;
import com.atlas.enterprise.task.application.TaskListApplicationService;
import com.atlas.enterprise.task.application.TaskListPageView;
import com.atlas.enterprise.task.application.TaskListQuery;
import com.atlas.enterprise.task.application.TaskWorkspaceApplicationService;
import com.atlas.enterprise.task.application.TaskWorkspaceView;
import com.atlas.enterprise.task.application.TaskWorkflowRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentMessageApplicationService {
    private final AgentRequestInterpreter requestInterpreter;
    private final TaskApplicationService tasks;
    private final TaskListApplicationService taskList;
    private final TaskWorkspaceApplicationService workspaces;
    private final TaskWorkflowRunner workflow;
    private final AgentConversationRepository conversations;
    private final Clock clock;

    public AgentMessageApplicationService(
        AgentRequestInterpreter requestInterpreter,
        TaskApplicationService tasks,
        TaskListApplicationService taskList,
        TaskWorkspaceApplicationService workspaces,
        TaskWorkflowRunner workflow,
        AgentConversationRepository conversations,
        Clock clock
    ) {
        this.requestInterpreter = requestInterpreter;
        this.tasks = tasks;
        this.taskList = taskList;
        this.workspaces = workspaces;
        this.workflow = workflow;
        this.conversations = conversations;
        this.clock = clock;
    }

    @Transactional
    public AgentMessageView handle(AgentMessageCommand command) {
        if (isBlank(command.idempotencyKey())) {
            throw new AgentMessageValidationException(
                "Idempotency-Key must not be blank"
            );
        }
        if (isBlank(command.operatorId())) {
            throw new AgentMessageValidationException(
                "X-Operator-Id must not be blank"
            );
        }
        AgentConversation conversation = resolveConversation(command);
        Optional<AgentStoredMessage> replay = conversations
            .findAssistantByIdempotencyKey(
                conversation.conversationId(),
                command.idempotencyKey()
            );
        if (replay.isPresent()) {
            return replay(replay.get(), command.operatorId());
        }

        AgentMessageCommand contextualCommand = contextualize(
            command,
            conversation
        );
        ParsedAgentRequest parsed = contextualize(
            requestInterpreter.interpret(
                contextualCommand.message(),
                contextualCommand.taskId() != null
            ),
            conversation,
            contextualCommand
        );
        Instant now = clock.instant();
        conversations.appendMessage(new AgentStoredMessage(
            UUID.randomUUID(),
            conversation.conversationId(),
            AgentMessageRole.USER,
            contextualCommand.message(),
            null,
            parsed.intent(),
            parsed.companyQuery(),
            contextualCommand.taskId(),
            contextualCommand.idempotencyKey(),
            List.of(),
            List.of(),
            now
        ));

        AgentMessageView result = switch (parsed.intent()) {
            case CREATE_RISK_REPORT_TASK ->
                createTask(contextualCommand, parsed);
            case QUERY_TASK_STATUS -> queryTask(contextualCommand, parsed);
            case UNSUPPORTED_SCOPE -> unsupported(
                conversation.conversationId(),
                parsed
            );
            case UNKNOWN -> needsCompanyOrTask(
                conversation.conversationId(),
                parsed
            );
        };
        result = withConversation(result, conversation.conversationId());
        UUID taskId = result.workspace() == null
            ? contextualCommand.taskId()
            : result.workspace().task().taskId();
        AgentConversation updated = conversation.withContext(
            taskId,
            result.companyQuery(),
            null,
            now
        );
        conversations.saveConversation(updated);
        conversations.appendMessage(new AgentStoredMessage(
            result.messageId(),
            conversation.conversationId(),
            AgentMessageRole.ASSISTANT,
            result.assistantMessage(),
            result.type(),
            result.parsedIntent(),
            result.companyQuery(),
            taskId,
            contextualCommand.idempotencyKey(),
            result.requiredInputs(),
            result.suggestedActions(),
            now
        ));
        return result;
    }

    private AgentMessageView createTask(
        AgentMessageCommand command,
        ParsedAgentRequest parsed
    ) {
        var task = tasks.create(new CreateTaskCommand(
            command.message(),
            parsed.companyQuery(),
            null,
            command.operatorId(),
            command.idempotencyKey()
        ));
        if (!task.operatorId().equals(command.operatorId())) {
            throw new AgentTaskAccessDeniedException();
        }
        workflow.run(
            task.taskId(),
            "agent-create-" + task.taskId(),
            "agent-auto-worker"
        );
        TaskWorkspaceView workspace = authorizedWorkspace(
            task.taskId(),
            command.operatorId()
        );
        return response(
            command.conversationId(),
            AgentResponseType.TASK_CREATED,
            "已启动%s的企业风险排查任务 %s。企业数据采集、公开信息检索和无需人工介入的步骤已自动连续执行。"
                .formatted(parsed.companyQuery(), task.taskNo()),
            parsed,
            workspace,
            List.of(),
            suggestedActions(workspace)
        );
    }

    private AgentMessageView queryTask(
        AgentMessageCommand command,
        ParsedAgentRequest parsed
    ) {
        TaskWorkspaceView workspace;
        if (command.taskId() != null) {
            workspace = authorizedWorkspace(
                command.taskId(),
                command.operatorId()
            );
        } else if (isUuid(parsed.taskReference())) {
            workspace = authorizedWorkspace(
                UUID.fromString(parsed.taskReference()),
                command.operatorId()
            );
        } else {
            String query = parsed.taskReference() != null
                ? parsed.taskReference()
                : parsed.companyQuery();
            if (query == null) {
                return response(
                    command.conversationId(),
                    AgentResponseType.NEEDS_INPUT,
                    "请提供任务编号、任务 ID 或完整企业名称，我才能查询对应任务。",
                    parsed,
                    null,
                    List.of(new AgentMessageView.RequiredInput(
                        "TASK_REFERENCE",
                        "任务编号、任务 ID 或完整企业名称",
                        true
                    )),
                    List.of()
                );
            }
            TaskListPageView page = taskList.list(new TaskListQuery(
                query,
                Set.of(),
                command.operatorId(),
                1,
                null
            ));
            if (page.items().isEmpty()) {
                return response(
                    command.conversationId(),
                    AgentResponseType.NEEDS_INPUT,
                    "没有找到与“%s”匹配的任务，请检查任务编号或企业名称。"
                        .formatted(query),
                    parsed,
                    null,
                    List.of(new AgentMessageView.RequiredInput(
                        "TASK_REFERENCE",
                        "有效的任务编号、任务 ID 或完整企业名称",
                        true
                    )),
                    List.of()
                );
            }
            workspace = authorizedWorkspace(
                page.items().getFirst().task().taskId(),
                command.operatorId()
            );
        }
        if (requestsReportRefresh(command.message())
            && workspace.task().status() == TaskStatus.COMPLETED) {
            var refreshedTask = tasks.create(new CreateTaskCommand(
                command.message(),
                workspace.task().companyQuery(),
                null,
                command.operatorId(),
                command.idempotencyKey() + ":refresh"
            ));
            workflow.run(
                refreshedTask.taskId(),
                "agent-refresh-" + refreshedTask.taskId(),
                "agent-auto-worker"
            );
            workspace = authorizedWorkspace(
                refreshedTask.taskId(),
                command.operatorId()
            );
            return response(
                command.conversationId(),
                AgentResponseType.TASK_CREATED,
                "已创建一次新的企业风险排查任务 %s。系统会先刷新企业信息，再重新检索公开信息、研判风险并生成新报告。"
                    .formatted(refreshedTask.taskNo()),
                parsed,
                workspace,
                List.of(),
                suggestedActions(workspace)
            );
        }
        return response(
            command.conversationId(),
            AgentResponseType.TASK_STATUS,
            statusMessage(workspace),
            parsed,
            workspace,
            List.of(),
            suggestedActions(workspace)
        );
    }

    private static AgentMessageView unsupported(
        UUID conversationId,
        ParsedAgentRequest parsed
    ) {
        return response(
            conversationId,
            AgentResponseType.UNSUPPORTED_SCOPE,
            "当前版本只处理单企业风险排查与报告生成，招商线索分析尚未开放。我没有创建任务。",
            parsed,
            null,
            List.of(),
            List.of()
        );
    }

    private static AgentMessageView needsCompanyOrTask(
        UUID conversationId,
        ParsedAgentRequest parsed
    ) {
        return response(
            conversationId,
            AgentResponseType.NEEDS_INPUT,
            "请给出完整企业名称或统一社会信用代码，并说明要排查该企业或生成风险报告。",
            parsed,
            null,
            List.of(new AgentMessageView.RequiredInput(
                "COMPANY_QUERY",
                "完整企业名称或统一社会信用代码",
                true
            )),
            List.of()
        );
    }

    private static String statusMessage(TaskWorkspaceView workspace) {
        return "任务 %s 当前状态为“%s”，建议下一步“%s”。"
            .formatted(
                workspace.task().taskNo(),
                statusLabel(workspace.task().status()),
                actionLabel(workspace.nextAction())
            );
    }

    private static List<AgentMessageView.SuggestedAction> suggestedActions(
        TaskWorkspaceView workspace
    ) {
        UUID taskId = workspace.task().taskId();
        return switch (workspace.nextAction()) {
            case EXECUTE_TASK -> List.of(action(
                "EXECUTE_TASK",
                "继续执行",
                "POST",
                "/api/tasks/" + taskId + "/execute"
            ));
            case CONFIRM_SUBJECT -> List.of(action(
                "CONFIRM_SUBJECT",
                "确认企业主体",
                "POST",
                "/api/tasks/" + taskId + "/subject-confirmation"
            ));
            case REVIEW_EVIDENCE -> List.of(action(
                "REVIEW_EVIDENCE",
                "处理待核验证据",
                "GET",
                "/api/tasks/" + taskId + "/public-intelligence/evidence"
            ));
            case REVIEW_SUBJECT_DATA -> List.of(action(
                "REVIEW_SUBJECT_DATA",
                "核对主体数据冲突",
                "POST",
                "/api/tasks/" + taskId
                    + "/subject-data-conflict-resolution"
            ));
            case CALCULATE_RISK -> List.of(action(
                "CALCULATE_RISK",
                "计算风险分",
                "POST",
                "/api/tasks/" + taskId
                    + "/risk-score/calculate-from-confirmed-evidence"
            ));
            case CONFIRM_REVIEW -> List.of(action(
                "CONFIRM_REVIEW",
                "确认研判并生成报告",
                "POST",
                "/api/tasks/" + taskId + "/operator-confirmation"
            ));
            case GENERATE_REPORT, RETRY_REPORT -> List.of(action(
                workspace.nextAction().name(),
                workspace.nextAction()
                    == TaskWorkspaceView.NextAction.GENERATE_REPORT
                    ? "生成报告"
                    : "重试生成报告",
                "POST",
                "/api/tasks/" + taskId + "/reports"
            ));
            case RETRY_TASK -> List.of(action(
                "RETRY_TASK",
                "重试失败步骤",
                "POST",
                "/api/tasks/" + taskId + "/retry"
            ));
            case DOWNLOAD_REPORT -> downloadAction(workspace, taskId);
            case WAIT, NONE -> List.of();
        };
    }

    private static List<AgentMessageView.SuggestedAction> downloadAction(
        TaskWorkspaceView workspace,
        UUID taskId
    ) {
        ReportVersion report = workspace.reports().stream()
            .filter(item -> item.status() == ReportStatus.GENERATED)
            .findFirst()
            .orElse(null);
        if (report == null) {
            return List.of();
        }
        return List.of(action(
            "DOWNLOAD_REPORT",
            "下载最新报告",
            "GET",
            "/api/tasks/" + taskId + "/reports/latest/download"
        ));
    }

    private static boolean requestsReportRefresh(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        return normalized.contains("更新报告")
            || normalized.contains("刷新报告")
            || normalized.contains("重新生成报告")
            || normalized.contains("生成最新报告");
    }

    private static AgentMessageView.SuggestedAction action(
        String code,
        String label,
        String method,
        String endpoint
    ) {
        return new AgentMessageView.SuggestedAction(
            code,
            label,
            method,
            endpoint
        );
    }

    private static AgentMessageView response(
        UUID conversationId,
        AgentResponseType type,
        String assistantMessage,
        ParsedAgentRequest parsed,
        TaskWorkspaceView workspace,
        List<AgentMessageView.RequiredInput> requiredInputs,
        List<AgentMessageView.SuggestedAction> suggestedActions
    ) {
        return new AgentMessageView(
            conversationId,
            UUID.randomUUID(),
            type,
            assistantMessage,
            parsed.intent(),
            parsed.companyQuery(),
            workspace,
            requiredInputs,
            suggestedActions
        );
    }

    private static String statusLabel(TaskStatus status) {
        return switch (status) {
            case CREATED -> "待执行";
            case RESOLVING_SUBJECT -> "正在识别企业主体";
            case WAITING_SUBJECT_CONFIRMATION -> "等待确认企业主体";
            case WAITING_SUBJECT_DATA_REVIEW -> "等待核对主体数据冲突";
            case LOADING_PREVIOUS_REPORT -> "正在准备企业数据（历史任务兼容）";
            case COLLECTING_STRUCTURED_DATA -> "正在采集企业数据";
            case SEARCHING_PUBLIC_INTELLIGENCE -> "正在检索公开信息";
            case CALCULATING_RISK -> "等待计算风险分";
            case WAITING_OPERATOR_CONFIRMATION -> "等待运营确认";
            case GENERATING_REPORT -> "正在生成报告";
            case COMPLETED -> "已完成";
            case SOURCE_FAILED -> "数据查询失败";
            case MODEL_FAILED -> "模型处理失败";
            case REPORT_FAILED -> "报告生成失败";
            case CANCELLED -> "已取消";
        };
    }

    private static String actionLabel(TaskWorkspaceView.NextAction action) {
        return switch (action) {
            case EXECUTE_TASK -> "继续执行";
            case CONFIRM_SUBJECT -> "确认企业主体";
            case REVIEW_EVIDENCE -> "处理待核验证据";
            case REVIEW_SUBJECT_DATA -> "核对主体数据冲突";
            case CALCULATE_RISK -> "计算风险分";
            case CONFIRM_REVIEW -> "确认研判结果";
            case GENERATE_REPORT -> "生成报告";
            case RETRY_TASK -> "重试失败步骤";
            case RETRY_REPORT -> "重试生成报告";
            case WAIT -> "等待当前步骤完成";
            case DOWNLOAD_REPORT -> "下载最新报告";
            case NONE -> "暂无可执行操作";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private TaskWorkspaceView authorizedWorkspace(
        UUID taskId,
        String operatorId
    ) {
        TaskWorkspaceView workspace = workspaces.get(taskId);
        if (!workspace.task().operatorId().equals(operatorId)) {
            throw new AgentTaskAccessDeniedException();
        }
        return workspace;
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private AgentConversation resolveConversation(
        AgentMessageCommand command
    ) {
        if (command.conversationId() == null) {
            Instant now = clock.instant();
            String title = command.message().trim();
            title = title.substring(0, Math.min(title.length(), 128));
            return conversations.saveConversation(new AgentConversation(
                UUID.randomUUID(),
                command.operatorId(),
                title,
                command.taskId(),
                null,
                null,
                now,
                now
            ));
        }
        AgentConversation conversation = conversations
            .findConversation(command.conversationId())
            .orElseThrow(() ->
                new AgentConversationNotFoundException(
                    command.conversationId()
                )
            );
        AgentConversationApplicationService.authorize(
            conversation,
            command.operatorId()
        );
        return conversation;
    }

    private static AgentMessageCommand contextualize(
        AgentMessageCommand command,
        AgentConversation conversation
    ) {
        return new AgentMessageCommand(
            conversation.conversationId(),
            command.message(),
            command.taskId() == null
                ? conversation.taskId()
                : command.taskId(),
            null,
            command.operatorId(),
            command.idempotencyKey()
        );
    }

    private static ParsedAgentRequest contextualize(
        ParsedAgentRequest parsed,
        AgentConversation conversation,
        AgentMessageCommand command
    ) {
        if (parsed.intent() != AgentIntent.UNKNOWN) {
            return parsed;
        }
        if (command.taskId() != null) {
            return new ParsedAgentRequest(
                AgentIntent.QUERY_TASK_STATUS,
                conversation.companyQuery(),
                command.taskId().toString()
            );
        }
        if (conversation.companyQuery() != null) {
            return new ParsedAgentRequest(
                AgentIntent.CREATE_RISK_REPORT_TASK,
                conversation.companyQuery(),
                null
            );
        }
        return parsed;
    }

    private AgentMessageView replay(
        AgentStoredMessage stored,
        String operatorId
    ) {
        TaskWorkspaceView workspace = stored.taskId() == null
            ? null
            : authorizedWorkspace(stored.taskId(), operatorId);
        return new AgentMessageView(
            stored.conversationId(),
            stored.messageId(),
            stored.responseType(),
            stored.content(),
            stored.parsedIntent(),
            stored.companyQuery(),
            workspace,
            stored.requiredInputs(),
            stored.suggestedActions()
        );
    }

    private static AgentMessageView withConversation(
        AgentMessageView view,
        UUID conversationId
    ) {
        return new AgentMessageView(
            conversationId,
            view.messageId(),
            view.type(),
            view.assistantMessage(),
            view.parsedIntent(),
            view.companyQuery(),
            view.workspace(),
            view.requiredInputs(),
            view.suggestedActions()
        );
    }
}
