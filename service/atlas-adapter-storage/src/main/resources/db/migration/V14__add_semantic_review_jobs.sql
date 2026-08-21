CREATE TABLE evidence_semantic_review_job (
    review_job_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_count INTEGER NOT NULL,
    processed_count INTEGER NOT NULL,
    reviewed_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    provider VARCHAR(128),
    model VARCHAR(256),
    error_message VARCHAR(2000),
    cancel_requested BOOLEAN NOT NULL,
    active_task_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_semantic_review_job_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT uq_semantic_review_active_task UNIQUE (active_task_id),
    CONSTRAINT ck_semantic_review_status CHECK (
        status IN (
            'QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED',
            'PARTIAL_FAILED', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_semantic_review_counts CHECK (
        total_count >= 0 AND processed_count >= 0
        AND reviewed_count >= 0 AND failed_count >= 0
    )
);

CREATE INDEX idx_semantic_review_task_created
    ON evidence_semantic_review_job(task_id, created_at DESC);
