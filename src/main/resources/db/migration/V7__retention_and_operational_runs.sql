CREATE TABLE maintenance_runs (
    id CHAR(36) PRIMARY KEY,
    job_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    details VARCHAR(2000) NOT NULL DEFAULT '',
    INDEX idx_maintenance_type_time (job_type, started_at)
);
