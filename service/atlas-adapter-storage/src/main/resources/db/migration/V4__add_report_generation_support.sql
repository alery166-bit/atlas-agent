ALTER TABLE report_version ADD COLUMN input_hash VARCHAR(64);
ALTER TABLE report_version ADD COLUMN mime_type VARCHAR(160);
ALTER TABLE report_version ADD COLUMN file_size BIGINT;
ALTER TABLE report_version ADD COLUMN parsed_previous_json TEXT;
ALTER TABLE report_version ADD COLUMN diff_json TEXT;
ALTER TABLE report_version ADD COLUMN failure_reason TEXT;

CREATE UNIQUE INDEX uq_report_task_input
    ON report_version(task_id, input_hash);

CREATE INDEX idx_report_task_version
    ON report_version(task_id, report_version_no DESC);
