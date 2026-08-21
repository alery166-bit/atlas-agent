ALTER TABLE public_search_batch
    ADD COLUMN source_scope VARCHAR(64) NOT NULL DEFAULT 'GENERAL_WEB';

CREATE INDEX idx_public_search_batch_task_scope
    ON public_search_batch (task_id, source_scope, searched_at);
