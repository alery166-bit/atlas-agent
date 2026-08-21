CREATE TABLE subject_data_conflict_resolution (
    resolution_id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES investigation_task(task_id),
    data_snapshot_id UUID NOT NULL REFERENCES data_snapshot(snapshot_id),
    decision VARCHAR(32) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_subject_conflict_resolution_snapshot
        UNIQUE (task_id, data_snapshot_id),
    CONSTRAINT ck_subject_conflict_resolution_decision
        CHECK (decision IN ('ACCEPT_MASTER'))
);

CREATE INDEX idx_subject_conflict_resolution_task_time
    ON subject_data_conflict_resolution(task_id, resolved_at DESC);
