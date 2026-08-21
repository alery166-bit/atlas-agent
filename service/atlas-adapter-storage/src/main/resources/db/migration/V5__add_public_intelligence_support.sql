CREATE TABLE public_search_batch (
    search_batch_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    source_snapshot_id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_mode VARCHAR(32) NOT NULL,
    query_text TEXT NOT NULL,
    target_risk VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_count INTEGER NOT NULL,
    failure_code VARCHAR(64),
    failure_message TEXT,
    searched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_public_search_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT fk_public_search_snapshot
        FOREIGN KEY (source_snapshot_id) REFERENCES data_snapshot(snapshot_id),
    CONSTRAINT ck_public_search_result_count CHECK (result_count >= 0)
);

CREATE INDEX idx_public_search_task_time
    ON public_search_batch(task_id, searched_at);

ALTER TABLE evidence ADD COLUMN search_batch_id UUID;
ALTER TABLE evidence ADD COLUMN risk_type VARCHAR(64) DEFAULT 'OTHER' NOT NULL;
ALTER TABLE evidence ADD COLUMN source_domain VARCHAR(255);
ALTER TABLE evidence ADD COLUMN query_text TEXT;
ALTER TABLE evidence ADD COLUMN rank_no INTEGER;
ALTER TABLE evidence ADD COLUMN evidence_grade VARCHAR(16) DEFAULT 'LEAD' NOT NULL;

ALTER TABLE evidence ADD CONSTRAINT fk_evidence_search_batch
    FOREIGN KEY (search_batch_id) REFERENCES public_search_batch(search_batch_id);

CREATE INDEX idx_evidence_task_verification
    ON evidence(task_id, verification_status);
CREATE INDEX idx_evidence_task_normalized_url
    ON evidence(task_id, normalized_url);

CREATE TABLE evidence_decision (
    decision_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_evidence_decision_task
        FOREIGN KEY (task_id) REFERENCES investigation_task(task_id),
    CONSTRAINT fk_evidence_decision_evidence
        FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
);

CREATE INDEX idx_evidence_decision_task_time
    ON evidence_decision(task_id, decided_at);
