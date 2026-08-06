-- Alinea la columna creada en V11 con el nombre usado por MasterDataImportService.
-- No se modifica V11 porque las migraciones Flyway ya aplicadas son inmutables.
ALTER TABLE master_import_rows RENAME COLUMN num_row TO row_number;
