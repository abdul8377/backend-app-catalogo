ALTER TABLE products ADD COLUMN company_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE products ADD COLUMN brand_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE products ADD COLUMN category_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE products ADD COLUMN subcategory_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE products ADD COLUMN subcategory VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE products ADD COLUMN product_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE';

CREATE TABLE product_imports (
    id CHAR(36) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    template_version VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    valid_rows INT NOT NULL DEFAULT 0,
    warning_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    confirmed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uq_product_import_hash (file_hash)
);

CREATE TABLE product_import_rows (
    id CHAR(36) PRIMARY KEY,
    import_id CHAR(36) NOT NULL,
    source_row_number INT NOT NULL,
    family_code VARCHAR(100) NOT NULL,
    product_id CHAR(36) NULL,
    expected_version BIGINT NULL,
    action VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    candidate_json LONGTEXT NOT NULL,
    messages_json LONGTEXT NOT NULL,
    result_product_id CHAR(36) NULL,
    result_version BIGINT NULL,
    processed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uq_product_import_row (import_id, source_row_number),
    INDEX idx_product_import_rows_status (import_id, status),
    CONSTRAINT fk_product_import_row FOREIGN KEY (import_id) REFERENCES product_imports(id)
);

CREATE TABLE stored_files (
    id CHAR(36) PRIMARY KEY,
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_type VARCHAR(80) NULL,
    owner_id VARCHAR(160) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    INDEX idx_stored_file_owner (owner_type, owner_id)
);
