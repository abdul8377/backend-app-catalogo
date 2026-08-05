package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class V12__link_products_to_catalog_masters extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        ensureColumn(connection, "company_id", "VARCHAR(160)");
        ensureColumn(connection, "brand_id", "VARCHAR(160)");
        ensureColumn(connection, "category_id", "VARCHAR(160)");
        ensureColumn(connection, "subcategory", "VARCHAR(160) NOT NULL DEFAULT ''");
        ensureColumn(connection, "subcategory_id", "VARCHAR(160)");
        ensureColumn(connection, "product_type", "VARCHAR(20) NOT NULL DEFAULT 'SINGLE'");

        ensureNullable(connection, "company_id");
        ensureNullable(connection, "brand_id");
        ensureNullable(connection, "category_id");
        ensureNullable(connection, "subcategory_id");

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

    private void ensureNullable(Connection connection, String column) throws SQLException {
        if (isNullable(connection, column)) return;
        String database = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        String sql = database.contains("h2")
                ? "ALTER TABLE products ALTER COLUMN " + column + " DROP NOT NULL"
                : "ALTER TABLE products MODIFY COLUMN " + column + " VARCHAR(160) NULL";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean isNullable(Connection connection, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM products WHERE 1 = 0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if (metadata.getColumnName(index).equalsIgnoreCase(column)) {
                    return metadata.isNullable(index) != ResultSetMetaData.columnNoNulls;
                }
            }
        }
        return false;
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
