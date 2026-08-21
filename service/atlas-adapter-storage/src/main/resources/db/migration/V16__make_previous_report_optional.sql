-- Direct enterprise investigation no longer uses a previous DOCX as input.
-- Columns remain for historical task/report traceability.
ALTER TABLE investigation_task
    ALTER COLUMN previous_report_file_id DROP NOT NULL;

ALTER TABLE report_version
    ALTER COLUMN previous_report_uri DROP NOT NULL;
