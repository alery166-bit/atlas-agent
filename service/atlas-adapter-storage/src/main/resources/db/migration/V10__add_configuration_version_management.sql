CREATE TABLE configuration_definition (
    config_id UUID PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL UNIQUE,
    category VARCHAR(32) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description TEXT,
    secret_config BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE configuration_version (
    version_id UUID PRIMARY KEY,
    config_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    value_json TEXT NOT NULL,
    secret_ref VARCHAR(256),
    checksum VARCHAR(64) NOT NULL,
    validation_message TEXT,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    validated_by VARCHAR(64),
    validated_at TIMESTAMP WITH TIME ZONE,
    published_by VARCHAR(64),
    published_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_config_version_definition
        FOREIGN KEY (config_id) REFERENCES configuration_definition(config_id),
    CONSTRAINT uq_config_version_no UNIQUE (config_id, version_no),
    CONSTRAINT ck_config_version_status CHECK (
        status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'INACTIVE')
    )
);

CREATE TABLE configuration_binding (
    config_id UUID NOT NULL,
    environment VARCHAR(32) NOT NULL,
    active_version_id UUID NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (config_id, environment),
    CONSTRAINT fk_config_binding_definition
        FOREIGN KEY (config_id) REFERENCES configuration_definition(config_id),
    CONSTRAINT fk_config_binding_version
        FOREIGN KEY (active_version_id) REFERENCES configuration_version(version_id)
);

CREATE TABLE configuration_release (
    release_id UUID PRIMARY KEY,
    config_id UUID NOT NULL,
    environment VARCHAR(32) NOT NULL,
    from_version_id UUID,
    to_version_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    operator_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_config_release_definition
        FOREIGN KEY (config_id) REFERENCES configuration_definition(config_id),
    CONSTRAINT fk_config_release_from_version
        FOREIGN KEY (from_version_id) REFERENCES configuration_version(version_id),
    CONSTRAINT fk_config_release_to_version
        FOREIGN KEY (to_version_id) REFERENCES configuration_version(version_id),
    CONSTRAINT ck_config_release_action CHECK (
        action IN ('PUBLISH', 'ROLLBACK')
    )
);

CREATE TABLE task_configuration_snapshot (
    config_snapshot_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    environment VARCHAR(32) NOT NULL,
    manifest_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    frozen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_task_config_snapshot_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT uq_task_config_snapshot UNIQUE (task_id, environment)
);

CREATE INDEX idx_config_version_status
    ON configuration_version(config_id, status, version_no DESC);
CREATE INDEX idx_config_release_time
    ON configuration_release(config_id, environment, occurred_at DESC);
CREATE INDEX idx_task_config_snapshot_hash
    ON task_configuration_snapshot(content_hash);
