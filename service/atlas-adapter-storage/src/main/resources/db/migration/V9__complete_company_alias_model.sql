ALTER TABLE company_alias
    ADD COLUMN relation_type VARCHAR(32) NOT NULL DEFAULT 'OTHER';

ALTER TABLE company_alias
    ADD COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED';

ALTER TABLE company_alias
    ADD COLUMN source_evidence TEXT;

ALTER TABLE company_alias
    ADD COLUMN created_by VARCHAR(128);

ALTER TABLE company_alias
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_company_alias_company_status
    ON company_alias(atlas_company_id, verification_status);
