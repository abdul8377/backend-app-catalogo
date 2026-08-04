ALTER TABLE sync_records ADD COLUMN last_sequence BIGINT NOT NULL DEFAULT 0;

UPDATE sync_records r
SET r.last_sequence = COALESCE((
    SELECT MAX(c.sequence)
    FROM sync_change_log c
    WHERE c.entity_type = r.entity_type
      AND c.entity_id = r.entity_id
), 0);

CREATE INDEX idx_sync_record_bootstrap ON sync_records (last_sequence, entity_type, entity_id);

ALTER TABLE sync_change_log ADD COLUMN conflict_id CHAR(36) NULL;
CREATE INDEX idx_change_conflict ON sync_change_log (conflict_id);

ALTER TABLE sync_processed_events ADD COLUMN conflict_id CHAR(36) NULL;

ALTER TABLE sync_conflicts ADD COLUMN resolution_version BIGINT NULL;
ALTER TABLE sync_conflicts ADD COLUMN resolution_sequence BIGINT NULL;

ALTER TABLE stored_files ADD COLUMN file_type VARCHAR(40) NOT NULL DEFAULT 'DOCUMENT';
ALTER TABLE stored_files ADD COLUMN expires_at TIMESTAMP(6) NULL;
ALTER TABLE stored_files ADD COLUMN uploaded_at TIMESTAMP(6) NULL;
CREATE INDEX idx_stored_file_expiry ON stored_files (status, expires_at);
