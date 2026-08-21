ALTER TABLE evidence_semantic_review_job
    ADD COLUMN model_suggested_score DECIMAL(8,4);

ALTER TABLE evidence_semantic_review_job
    ADD COLUMN model_suggested_risk_level VARCHAR(32);

ALTER TABLE evidence_semantic_review_job
    ADD COLUMN model_score_evidence_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE evidence_semantic_review_job
    ADD COLUMN advisory_rule_version VARCHAR(64);

ALTER TABLE evidence_semantic_review_job
    ADD CONSTRAINT ck_semantic_review_suggested_score CHECK (
        model_suggested_score IS NULL
        OR (model_suggested_score >= 0 AND model_suggested_score <= 10)
    );
