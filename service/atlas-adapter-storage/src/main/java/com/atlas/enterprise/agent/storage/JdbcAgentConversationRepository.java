package com.atlas.enterprise.agent.storage;

import com.atlas.enterprise.agent.application.AgentConversation;
import com.atlas.enterprise.agent.application.AgentIntent;
import com.atlas.enterprise.agent.application.AgentMessageRole;
import com.atlas.enterprise.agent.application.AgentMessageView;
import com.atlas.enterprise.agent.application.AgentResponseType;
import com.atlas.enterprise.agent.application.AgentStoredMessage;
import com.atlas.enterprise.agent.port.AgentConversationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentConversationRepository
    implements AgentConversationRepository {
    private static final String CONVERSATION_COLUMNS = """
        SELECT conversation_id, operator_id, title, task_id, company_query,
               previous_report_file_id, created_at, updated_at
          FROM agent_conversation
        """;
    private static final String MESSAGE_COLUMNS = """
        SELECT message_seq, message_id, conversation_id, role, content, response_type,
               parsed_intent, company_query, task_id, idempotency_key,
               required_inputs_json, suggested_actions_json, created_at
          FROM agent_message
        """;
    private static final TypeReference<
        List<AgentMessageView.RequiredInput>
    > REQUIRED_INPUTS = new TypeReference<>() {
    };
    private static final TypeReference<
        List<AgentMessageView.SuggestedAction>
    > SUGGESTED_ACTIONS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAgentConversationRepository(
        JdbcClient jdbc,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentConversation saveConversation(
        AgentConversation conversation
    ) {
        int updated = jdbc.sql("""
                UPDATE agent_conversation
                   SET title = :title,
                       task_id = :taskId,
                       company_query = :companyQuery,
                       previous_report_file_id = :previousReportFileId,
                       updated_at = :updatedAt
                 WHERE conversation_id = :conversationId
                """)
            .param("title", conversation.title())
            .param("taskId", conversation.taskId())
            .param("companyQuery", conversation.companyQuery())
            .param(
                "previousReportFileId",
                conversation.previousReportFileId()
            )
            .param("updatedAt", utc(conversation.updatedAt()))
            .param("conversationId", conversation.conversationId())
            .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO agent_conversation (
                        conversation_id, operator_id, title, task_id,
                        company_query, previous_report_file_id,
                        created_at, updated_at
                    ) VALUES (
                        :conversationId, :operatorId, :title, :taskId,
                        :companyQuery, :previousReportFileId,
                        :createdAt, :updatedAt
                    )
                    """)
                .param("conversationId", conversation.conversationId())
                .param("operatorId", conversation.operatorId())
                .param("title", conversation.title())
                .param("taskId", conversation.taskId())
                .param("companyQuery", conversation.companyQuery())
                .param(
                    "previousReportFileId",
                    conversation.previousReportFileId()
                )
                .param("createdAt", utc(conversation.createdAt()))
                .param("updatedAt", utc(conversation.updatedAt()))
                .update();
        }
        return conversation;
    }

    @Override
    public Optional<AgentConversation> findConversation(UUID conversationId) {
        return jdbc.sql(
                CONVERSATION_COLUMNS
                    + " WHERE conversation_id = :conversationId"
            )
            .param("conversationId", conversationId)
            .query(this::mapConversation)
            .optional();
    }

    @Override
    public List<AgentConversation> findConversations(
        String operatorId,
        int limit
    ) {
        return jdbc.sql(
                CONVERSATION_COLUMNS
                    + """
                       WHERE operator_id = :operatorId
                         AND archived_at IS NULL
                       ORDER BY updated_at DESC, conversation_id DESC
                       LIMIT :limit
                      """
            )
            .param("operatorId", operatorId)
            .param("limit", limit)
            .query(this::mapConversation)
            .list();
    }

    @Override
    public boolean archiveConversation(
        UUID conversationId,
        String operatorId,
        Instant archivedAt
    ) {
        return jdbc.sql("""
                UPDATE agent_conversation
                   SET archived_at = :archivedAt,
                       updated_at = :archivedAt
                 WHERE conversation_id = :conversationId
                   AND operator_id = :operatorId
                   AND archived_at IS NULL
                """)
            .param("archivedAt", utc(archivedAt))
            .param("conversationId", conversationId)
            .param("operatorId", operatorId)
            .update() == 1;
    }

    @Override
    public AgentStoredMessage appendMessage(AgentStoredMessage message) {
        jdbc.sql("""
                INSERT INTO agent_message (
                    message_id, conversation_id, role, content, response_type,
                    parsed_intent, company_query, task_id, idempotency_key,
                    required_inputs_json, suggested_actions_json, created_at
                ) VALUES (
                    :messageId, :conversationId, :role, :content,
                    :responseType, :parsedIntent, :companyQuery, :taskId,
                    :idempotencyKey, :requiredInputs, :suggestedActions,
                    :createdAt
                )
                """)
            .param("messageId", message.messageId())
            .param("conversationId", message.conversationId())
            .param("role", message.role().name())
            .param("content", message.content())
            .param(
                "responseType",
                message.responseType() == null
                    ? null
                    : message.responseType().name()
            )
            .param(
                "parsedIntent",
                message.parsedIntent() == null
                    ? null
                    : message.parsedIntent().name()
            )
            .param("companyQuery", message.companyQuery())
            .param("taskId", message.taskId())
            .param("idempotencyKey", message.idempotencyKey())
            .param("requiredInputs", write(message.requiredInputs()))
            .param("suggestedActions", write(message.suggestedActions()))
            .param("createdAt", utc(message.createdAt()))
            .update();
        return message;
    }

    @Override
    public List<AgentStoredMessage> findMessages(
        UUID conversationId,
        int limit
    ) {
        return jdbc.sql(
                MESSAGE_COLUMNS
                    + """
                       WHERE conversation_id = :conversationId
                       ORDER BY message_seq ASC
                       LIMIT :limit
                      """
            )
            .param("conversationId", conversationId)
            .param("limit", limit)
            .query(this::mapMessage)
            .list();
    }

    @Override
    public Optional<AgentStoredMessage> findAssistantByIdempotencyKey(
        UUID conversationId,
        String idempotencyKey
    ) {
        return jdbc.sql(
                MESSAGE_COLUMNS
                    + """
                       WHERE conversation_id = :conversationId
                         AND idempotency_key = :idempotencyKey
                         AND role = 'ASSISTANT'
                      """
            )
            .param("conversationId", conversationId)
            .param("idempotencyKey", idempotencyKey)
            .query(this::mapMessage)
            .optional();
    }

    private AgentConversation mapConversation(
        ResultSet rs,
        int rowNum
    ) throws SQLException {
        return new AgentConversation(
            rs.getObject("conversation_id", UUID.class),
            rs.getString("operator_id"),
            rs.getString("title"),
            rs.getObject("task_id", UUID.class),
            rs.getString("company_query"),
            rs.getString("previous_report_file_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private AgentStoredMessage mapMessage(
        ResultSet rs,
        int rowNum
    ) throws SQLException {
        String responseType = rs.getString("response_type");
        String parsedIntent = rs.getString("parsed_intent");
        return new AgentStoredMessage(
            rs.getObject("message_id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            AgentMessageRole.valueOf(rs.getString("role")),
            rs.getString("content"),
            responseType == null
                ? null
                : AgentResponseType.valueOf(responseType),
            parsedIntent == null
                ? null
                : AgentIntent.valueOf(parsedIntent),
            rs.getString("company_query"),
            rs.getObject("task_id", UUID.class),
            rs.getString("idempotency_key"),
            read(rs.getString("required_inputs_json"), REQUIRED_INPUTS),
            read(rs.getString("suggested_actions_json"), SUGGESTED_ACTIONS),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to serialize agent message metadata",
                exception
            );
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to deserialize agent message metadata",
                exception
            );
        }
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
