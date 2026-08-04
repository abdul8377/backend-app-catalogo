ALTER TABLE sync_conflicts ADD COLUMN resolved_by VARCHAR(120) NULL;
ALTER TABLE sync_conflicts ADD COLUMN resolution VARCHAR(40) NULL;
ALTER TABLE sync_conflicts ADD COLUMN resolution_payload LONGTEXT NULL;
ALTER TABLE sync_conflicts ADD COLUMN resolution_event_id CHAR(36) NULL;
