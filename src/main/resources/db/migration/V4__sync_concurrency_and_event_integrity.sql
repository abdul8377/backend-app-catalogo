ALTER TABLE sync_records ADD COLUMN technical_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_processed_events ADD COLUMN request_checksum CHAR(64) NULL;
ALTER TABLE sync_processed_events ADD COLUMN payload_version INT NOT NULL DEFAULT 1;
ALTER TABLE sync_processed_events ADD COLUMN schema_version VARCHAR(30) NULL;
