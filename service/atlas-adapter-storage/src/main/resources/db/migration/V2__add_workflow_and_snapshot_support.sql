ALTER TABLE investigation_task
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE investigation_task
    ADD COLUMN execution_owner VARCHAR(128);

ALTER TABLE investigation_task
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE investigation_task
    ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_task_execution_lease
    ON investigation_task(status, lease_expires_at);

ALTER TABLE task_step
    ADD COLUMN error_message TEXT;

ALTER TABLE data_snapshot
    ADD COLUMN company_changes_json TEXT NOT NULL DEFAULT '[]';

CREATE TABLE company_alias (
    alias_id UUID PRIMARY KEY,
    atlas_company_id UUID NOT NULL,
    alias_name VARCHAR(256) NOT NULL,
    alias_type VARCHAR(32) NOT NULL,
    source_system VARCHAR(64),
    source_record_id VARCHAR(128),
    valid_from TIMESTAMP WITH TIME ZONE,
    valid_to TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_alias_company
        FOREIGN KEY (atlas_company_id) REFERENCES atlas_company(atlas_company_id),
    CONSTRAINT uq_company_alias UNIQUE (atlas_company_id, alias_name, alias_type)
);

CREATE INDEX idx_company_alias_name ON company_alias(alias_name);
