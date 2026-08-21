ALTER TABLE evidence_semantic_review_job
    ADD COLUMN model_call_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE evidence_semantic_review_job
    ADD COLUMN prompt_token_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE evidence_semantic_review_job
    ADD COLUMN completion_token_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE evidence_semantic_review_job
    ADD COLUMN total_token_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE evidence_semantic_review_job
    ADD CONSTRAINT ck_semantic_review_usage CHECK (
        model_call_count >= 0
        AND prompt_token_count >= 0
        AND completion_token_count >= 0
        AND total_token_count >= 0
    );
