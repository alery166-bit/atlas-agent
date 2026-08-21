CREATE TABLE operator_confirmation (
    confirmation_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    data_snapshot_id UUID NOT NULL,
    score_snapshot_id UUID NOT NULL,
    review_state_hash VARCHAR(64) NOT NULL,
    confirmed_evidence_count INTEGER NOT NULL,
    rejected_evidence_count INTEGER NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    note TEXT NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_operator_confirmation_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT fk_operator_confirmation_snapshot
        FOREIGN KEY (data_snapshot_id) REFERENCES data_snapshot(snapshot_id),
    CONSTRAINT fk_operator_confirmation_score
        FOREIGN KEY (score_snapshot_id) REFERENCES risk_score_snapshot(score_snapshot_id),
    CONSTRAINT ck_operator_confirmation_confirmed_count
        CHECK (confirmed_evidence_count >= 0),
    CONSTRAINT ck_operator_confirmation_rejected_count
        CHECK (rejected_evidence_count >= 0),
    CONSTRAINT uq_operator_confirmation_task_state
        UNIQUE (task_id, review_state_hash)
);

CREATE INDEX idx_operator_confirmation_task_time
    ON operator_confirmation(task_id, confirmed_at);

ALTER TABLE report_version
    ADD COLUMN operator_confirmation_id UUID;

ALTER TABLE report_version
    ADD CONSTRAINT fk_report_operator_confirmation
        FOREIGN KEY (operator_confirmation_id)
        REFERENCES operator_confirmation(confirmation_id);
