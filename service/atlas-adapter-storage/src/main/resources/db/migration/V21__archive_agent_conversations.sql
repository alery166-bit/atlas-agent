ALTER TABLE agent_conversation
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_agent_conversation_active_operator
    ON agent_conversation (operator_id, archived_at, updated_at DESC);
