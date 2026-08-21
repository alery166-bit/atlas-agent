DROP INDEX IF EXISTS uq_risk_score_task_input;

CREATE INDEX idx_risk_score_task_input
    ON risk_score_snapshot(task_id, input_hash);
