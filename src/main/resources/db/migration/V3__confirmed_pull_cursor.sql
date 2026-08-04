ALTER TABLE sync_device_state ADD COLUMN last_delivered_cursor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_device_state ADD COLUMN last_acknowledged_cursor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_device_state ADD COLUMN last_error VARCHAR(1000) NULL;
UPDATE sync_device_state
SET last_delivered_cursor = last_pull_cursor,
    last_acknowledged_cursor = last_pull_cursor;
