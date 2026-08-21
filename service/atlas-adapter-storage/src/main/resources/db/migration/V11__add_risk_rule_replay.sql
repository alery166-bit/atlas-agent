CREATE TABLE risk_rule_replay_run (
    replay_id UUID PRIMARY KEY,
    version_id UUID NOT NULL,
    version_checksum VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    sample_count INTEGER NOT NULL,
    passed_count INTEGER NOT NULL,
    score_changed_count INTEGER NOT NULL,
    level_changed_count INTEGER NOT NULL,
    max_score_delta DECIMAL(8,4) NOT NULL,
    result_json TEXT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_rule_replay_version
        FOREIGN KEY (version_id) REFERENCES configuration_version(version_id),
    CONSTRAINT ck_rule_replay_status CHECK (status IN ('PASSED', 'FAILED')),
    CONSTRAINT ck_rule_replay_counts CHECK (
        sample_count >= 0 AND passed_count >= 0
        AND score_changed_count >= 0 AND level_changed_count >= 0
    )
);

CREATE INDEX idx_rule_replay_version_time
    ON risk_rule_replay_run(version_id, created_at DESC);
