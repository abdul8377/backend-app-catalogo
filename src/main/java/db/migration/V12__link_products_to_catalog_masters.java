package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class V12__link_products_to_catalog_masters extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        ensureColumn(connection, "company_id", "VARCHAR(160) NULL");
        ensureColumn(connection, "brand_id", "VARCHAR(160) NULL");
        ensureColumn(connection, "category_id", "VARCHAR(160) NULL");
        ensureColumn(connection, "subcategory", "VARCHAR(160) NOT NULL DEFAULT ''");
        ensureColumn(connection, "subcategory_id", "VARCHAR(160) NULL");
        ensureColumn(connection, "product_type", "VARCHAR(20) NOT NULL DEFAULT 'SINGLE'");

        ensureForeignKey(connection, "fk_product_company",
                "ALTER TABLE products ADD CONSTRAINT fk_product_company "
                        + "FOREIGN KEY (company_id) REFERENCES empresas(id)");
        ensureForeignKey(connection, "fk_product_brand",
                "ALTER TABLE products ADD CONSTRAINT fk_product_brand "
                        + "FOREIGN KEY (brand_id) REFERENCES marcas(id)");
        ensureForeignKey(connection, "fk_product_category",
                "ALTER TABLE products ADD CONSTRAINT fk_product_category "
                        + "FOREIGN KEY (category_id) REFERENCES categorias(id)");
        ensureForeignKey(connection, "fk_product_subcategory",
                "ALTER TABLE products ADD CONSTRAINT fk_product_subcategory "
                        + "FOREIGN KEY (subcategory_id) REFERENCES categorias(id)");

        ensureIndex(connection, "idx_product_company_id",
                "CREATE INDEX idx_product_company_id ON products(company_id)");
        ensureIndex(connection, "idx_product_brand_id",
                "CREATE INDEX idx_product_brand_id ON products(brand_id)");
        ensureIndex(connection, "idx_product_category_id",
                "CREATE INDEX idx_product_category_id ON products(category_id, subcategory_id)");
    }

    private void ensureColumn(Connection connection, String column, String definition) throws SQLException {
        if (columns(connection).contains(column.toLowerCase(Locale.ROOT))) return;
        executeIgnoringDuplicate(connection,
                "ALTER TABLE products ADD COLUMN " + column + " " + definition,
                "column");
    }

    private Set<String> columns(Connection connection) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM products WHERE 1 = 0")) {
            var metadata = rows.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                result.add(metadata.getColumnName(index).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private void ensureForeignKey(Connection connection, String name, String sql) throws SQLException {
        if (foreignKeys(connection).contains(name.toLowerCase(Locale.ROOT))) return;
        executeIgnoringDuplicate(connection, sql, "constraint");
    }

    private Set<String> foreignKeys(Connection connection) throws SQLException {
        Set<String> result = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : new String[]{"products", "PRODUCTS"}) {
            try (ResultSet rows = metadata.getImportedKeys(connection.getCatalog(), connection.getSchema(), table)) {
                while (rows.next()) {
                    String name = rows.getString("FK_NAME");
                    if (name != null) result.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private void ensureIndex(Connection connection, String name, String sql) throws SQLException {
        if (indexes(connection).contains(name.toLowerCase(Locale.ROOT))) return;
        executeIgnoringDuplicate(connection, sql, "index");
    }

    private Set<String> indexes(Connection connection) throws SQLException {
        Set<String> result = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : new String[]{"products", "PRODUCTS"}) {
            try (ResultSet rows = metadata.getIndexInfo(
                    connection.getCatalog(), connection.getSchema(), table, false, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if (name != null) result.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private void executeIgnoringDuplicate(Connection connection, String sql, String objectType) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            String message = exception.getMessage() == null
                    ? ""
                    : exception.getMessage().toLowerCase(Locale.ROOT);
            boolean duplicate = "42S21".equalsIgnoreCase(exception.getSQLState())
                    || exception.getErrorCode() == 1060
                    || message.contains("duplicate " + objectType)
                    || message.contains("already exists");
            if (!duplicate) throw exception;
        }
    }
}
