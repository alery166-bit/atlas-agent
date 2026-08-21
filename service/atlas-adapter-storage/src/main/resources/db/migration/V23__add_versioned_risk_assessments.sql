CREATE TABLE risk_assessment_revision (
    assessment_revision_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    score_snapshot_id UUID NOT NULL,
    data_snapshot_id UUID NOT NULL,
    revision_no INTEGER NOT NULL,
    trigger_type VARCHAR(40) NOT NULL,
    legacy_score DECIMAL(8,4),
    rule_calculated_score DECIMAL(8,4) NOT NULL,
    event_floor_score DECIMAL(8,4) NOT NULL,
    original_score DECIMAL(8,4) NOT NULL,
    final_score DECIMAL(8,4) NOT NULL,
    original_risk_level VARCHAR(32) NOT NULL,
    final_risk_level VARCHAR(32) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    source_labels_json TEXT NOT NULL,
    rule_labels_json TEXT NOT NULL,
    model_labels_json TEXT NOT NULL,
    final_labels_json TEXT NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_assessment_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT fk_assessment_score
        FOREIGN KEY (score_snapshot_id) REFERENCES risk_score_snapshot(score_snapshot_id),
    CONSTRAINT fk_assessment_snapshot
        FOREIGN KEY (data_snapshot_id) REFERENCES data_snapshot(snapshot_id),
    CONSTRAINT uq_assessment_task_revision UNIQUE (task_id, revision_no),
    CONSTRAINT ck_assessment_original_score CHECK (original_score >= 0 AND original_score <= 10),
    CONSTRAINT ck_assessment_final_score CHECK (final_score >= 0 AND final_score <= 10)
);

CREATE INDEX idx_assessment_task_created
    ON risk_assessment_revision(task_id, created_at);

CREATE INDEX idx_assessment_score_trigger
    ON risk_assessment_revision(score_snapshot_id, trigger_type);
