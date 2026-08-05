ALTER TABLE product_imports
    ADD COLUMN image_archive_name VARCHAR(255) NULL;

ALTER TABLE product_imports
    ADD COLUMN image_archive_hash CHAR(64) NULL;

ALTER TABLE product_imports
    ADD COLUMN image_archive_storage_key VARCHAR(500) NULL;

CREATE INDEX idx_product_import_image_hash ON product_imports(image_archive_hash);
