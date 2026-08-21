CREATE UNIQUE INDEX uq_risk_score_task_input
    ON risk_score_snapshot(task_id, input_hash);

CREATE INDEX idx_risk_score_task_calculated
    ON risk_score_snapshot(task_id, calculated_at);
