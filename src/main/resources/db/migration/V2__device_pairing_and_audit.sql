CREATE TABLE server_identity (
    singleton_id SMALLINT PRIMARY KEY,
    server_id CHAR(36) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE pairing_codes (
    id CHAR(36) PRIMARY KEY,
    code_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    used_by_device_id CHAR(36) NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    INDEX idx_pairing_status_expiry (status, expires_at)
);

ALTER TABLE devices ADD COLUMN app_version VARCHAR(40) NOT NULL DEFAULT '';
ALTER TABLE devices ADD COLUMN contract_version VARCHAR(20) NOT NULL DEFAULT '';
ALTER TABLE devices ADD COLUMN revoked_at TIMESTAMP(6) NULL;
ALTER TABLE devices ADD COLUMN token_rotated_at TIMESTAMP(6) NULL;

CREATE TABLE device_audit_log (
    id CHAR(36) PRIMARY KEY,
    device_id CHAR(36) NULL,
    action VARCHAR(40) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    details VARCHAR(1000) NOT NULL DEFAULT '',
    occurred_at TIMESTAMP(6) NOT NULL,
    INDEX idx_device_audit_device_time (device_id, occurred_at),
    CONSTRAINT fk_device_audit_device FOREIGN KEY (device_id) REFERENCES devices(id)
);
