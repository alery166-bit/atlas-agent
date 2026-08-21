CREATE TABLE evidence_content_snapshot (
    content_snapshot_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_url TEXT NOT NULL,
    final_url TEXT,
    http_status INTEGER,
    content_type VARCHAR(255),
    raw_content TEXT,
    extracted_text TEXT,
    raw_content_hash VARCHAR(64),
    extracted_text_hash VARCHAR(64),
    byte_length BIGINT NOT NULL,
    truncated BOOLEAN NOT NULL,
    failure_code VARCHAR(64),
    failure_message TEXT,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_evidence_content_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT fk_evidence_content_evidence
        FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id),
    CONSTRAINT ck_evidence_content_byte_length CHECK (byte_length >= 0)
);

CREATE INDEX idx_evidence_content_task_time
    ON evidence_content_snapshot(task_id, captured_at);
CREATE INDEX idx_evidence_content_evidence_time
    ON evidence_content_snapshot(evidence_id, captured_at);
