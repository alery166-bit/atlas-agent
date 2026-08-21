package com.atlas.enterprise.task.storage;

import com.atlas.enterprise.task.application.TaskEventRecord;
import com.atlas.enterprise.task.port.TaskEventStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaskEventStore implements TaskEventStore {
    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcTaskEventStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskEventRecord append(
        UUID taskId,
        String type,
        Map<String, String> payload,
        Instant occurredAt
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO task_event (task_id, event_type, payload_json, occurred_at)
                VALUES (:taskId, :eventType, :payloadJson, :occurredAt)
                """)
            .paramSource(new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("eventType", type)
                .addValue("payloadJson", write(payload))
                .addValue("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC)))
            .update(keyHolder, "event_id");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return task event id");
        }
        return new TaskEventRecord(key.longValue(), taskId, type, Map.copyOf(payload), occurredAt);
    }

    @Override
    public List<TaskEventRecord> findAfter(UUID taskId, long eventIdExclusive) {
        return jdbc.sql("""
                SELECT event_id, task_id, event_type, payload_json, occurred_at
                  FROM task_event
                 WHERE task_id = :taskId
                   AND event_id > :eventId
                 ORDER BY event_id
                """)
            .param("taskId", taskId)
            .param("eventId", eventIdExclusive)
            .query(this::map)
            .list();
    }

    private TaskEventRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new TaskEventRecord(
            rs.getLong("event_id"),
            rs.getObject("task_id", UUID.class),
            rs.getString("event_type"),
            read(rs.getString("payload_json")),
            rs.getObject("occurred_at", OffsetDateTime.class).toInstant()
        );
    }

    private String write(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize task event payload", exception);
        }
    }

    private Map<String, String> read(String payload) {
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot deserialize task event payload", exception);
        }
    }
}
