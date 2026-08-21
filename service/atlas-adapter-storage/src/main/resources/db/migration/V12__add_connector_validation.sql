CREATE TABLE connector_test_run (
    test_id UUID PRIMARY KEY,
    version_id UUID NOT NULL,
    version_checksum VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    latency_ms BIGINT NOT NULL,
    message TEXT NOT NULL,
    preview_json TEXT,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_connector_test_version
        FOREIGN KEY (version_id) REFERENCES configuration_version(version_id),
    CONSTRAINT ck_connector_test_status CHECK (status IN ('PASSED', 'FAILED')),
    CONSTRAINT ck_connector_test_latency CHECK (latency_ms >= 0)
);

CREATE INDEX idx_connector_test_version_time
    ON connector_test_run(version_id, created_at DESC);
