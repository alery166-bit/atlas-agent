CREATE TABLE golden_acceptance_suite (
    suite_id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    case_count INTEGER NOT NULL,
    confirmed_case_count INTEGER NOT NULL,
    verified_artifact_case_count INTEGER NOT NULL,
    manifest_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_golden_suite_status CHECK (status IN ('DRAFT', 'READY')),
    CONSTRAINT ck_golden_suite_counts CHECK (
        case_count >= 0 AND confirmed_case_count >= 0
        AND confirmed_case_count <= case_count
        AND verified_artifact_case_count >= 0
        AND verified_artifact_case_count <= case_count
    )
);

CREATE TABLE golden_acceptance_run (
    run_id UUID PRIMARY KEY,
    suite_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    case_count INTEGER NOT NULL,
    completed_case_count INTEGER NOT NULL,
    severe_subject_mismatch_count INTEGER NOT NULL,
    major_risk_count INTEGER NOT NULL,
    supported_major_risk_count INTEGER NOT NULL,
    explainable_score_count INTEGER NOT NULL,
    docx_pass_count INTEGER NOT NULL,
    critical_defect_count INTEGER NOT NULL,
    high_defect_count INTEGER NOT NULL,
    average_manual_minutes DECIMAL(10,2),
    result_json TEXT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_golden_run_suite
        FOREIGN KEY (suite_id) REFERENCES golden_acceptance_suite(suite_id),
    CONSTRAINT ck_golden_run_status CHECK (status IN ('PASSED', 'FAILED', 'INCOMPLETE'))
);

CREATE INDEX idx_golden_suite_created
    ON golden_acceptance_suite(created_at DESC);
CREATE INDEX idx_golden_run_suite_created
    ON golden_acceptance_run(suite_id, created_at DESC);
