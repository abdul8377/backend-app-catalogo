CREATE TABLE devices (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    platform VARCHAR(40) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    last_seen_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE sync_device_state (
    device_id CHAR(36) PRIMARY KEY,
    last_pull_cursor BIGINT NOT NULL DEFAULT 0,
    last_push_at TIMESTAMP(6) NULL,
    last_pull_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_sync_state_device FOREIGN KEY (device_id) REFERENCES devices(id)
);

CREATE TABLE sync_records (
    id CHAR(36) PRIMARY KEY,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(160) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_sync_record_entity UNIQUE (entity_type, entity_id),
    INDEX idx_sync_record_entity (entity_type, entity_id),
    INDEX idx_sync_record_updated (updated_at)
);

CREATE TABLE sync_change_log (
    sequence BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(160) NOT NULL,
    operation VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL,
    origin_device_id CHAR(36) NULL,
    payload_json LONGTEXT NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    INDEX idx_change_sequence (sequence)
);

CREATE TABLE sync_processed_events (
    event_id CHAR(36) PRIMARY KEY,
    device_id CHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    server_version BIGINT NULL,
    server_sequence BIGINT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    message VARCHAR(500) NULL,
    INDEX idx_processed_device (device_id),
    CONSTRAINT fk_processed_device FOREIGN KEY (device_id) REFERENCES devices(id)
);

CREATE TABLE sync_conflicts (
    id CHAR(36) PRIMARY KEY,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(160) NOT NULL,
    server_version BIGINT NOT NULL,
    client_base_version BIGINT NOT NULL,
    server_payload LONGTEXT NOT NULL,
    client_payload LONGTEXT NOT NULL,
    origin_device_id CHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6) NULL,
    INDEX idx_conflict_status (status),
    CONSTRAINT fk_conflict_device FOREIGN KEY (origin_device_id) REFERENCES devices(id)
);

CREATE TABLE products (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(250) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    company VARCHAR(160) NOT NULL DEFAULT '',
    brand VARCHAR(160) NOT NULL DEFAULT '',
    category VARCHAR(160) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL,
    aggregate_json LONGTEXT NOT NULL,
    origin_device_id CHAR(36) NULL,
    deleted_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_product_name (name),
    INDEX idx_product_status (status),
    INDEX idx_product_classification (company, brand, category)
);
