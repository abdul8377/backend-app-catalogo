package com.abdul.catalogo.synchronization.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class BootstrapSnapshotRepository {
    private static final String SNAPSHOT_CTE = """
            WITH historical AS (
                SELECT c.entity_type, c.entity_id, c.version,
                       CASE WHEN c.operation = 'DELETE' THEN TRUE ELSE FALSE END AS deleted,
                       c.payload_json, c.changed_at AS updated_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY c.entity_type, c.entity_id
                           ORDER BY c.sequence DESC
                       ) AS row_rank
                FROM sync_change_log c
                JOIN sync_records current_record
                  ON current_record.entity_type = c.entity_type
                 AND current_record.entity_id = c.entity_id
                WHERE current_record.last_sequence > ?
                  AND c.sequence <= ?
            ), snapshot_records AS (
                SELECT r.entity_type, r.entity_id, r.version, r.deleted, r.payload_json, r.updated_at
                FROM sync_records r
                WHERE r.last_sequence <= ?
                UNION ALL
                SELECT h.entity_type, h.entity_id, h.version, h.deleted, h.payload_json, h.updated_at
                FROM historical h
                WHERE h.row_rank = 1
            )
            """;
    private static final String SUPPORTED_TYPES = """
            'COMPANY', 'BRAND', 'CATEGORY', 'BRAND_CATEGORY', 'MEASUREMENT_UNIT',
            'CATEGORY_ATTRIBUTE', 'CATEGORY_ATTRIBUTE_OPTION', 'CATEGORY_ATTRIBUTE_UNIT',
            'LEGACY_ATTRIBUTE_DEFINITION', 'PRODUCT', 'CLIENT', 'ORDER_SHEET', 'ORDER',
            'ORDER_ITEM', 'QUOTE', 'QUOTE_ITEM', 'PREPARATION',
            'PREPARATION_STOCK_MOVEMENT', 'ORDER_LOAD', 'ORDER_HISTORY', 'ORDER_SHEET_HISTORY'
            """;
    private static final String DEPENDENCY_ORDER = """
            CASE entity_type
              WHEN 'COMPANY' THEN 10 WHEN 'BRAND' THEN 20 WHEN 'CATEGORY' THEN 30
              WHEN 'BRAND_CATEGORY' THEN 40 WHEN 'MEASUREMENT_UNIT' THEN 50
              WHEN 'CATEGORY_ATTRIBUTE' THEN 60 WHEN 'CATEGORY_ATTRIBUTE_OPTION' THEN 70
              WHEN 'CATEGORY_ATTRIBUTE_UNIT' THEN 80 WHEN 'LEGACY_ATTRIBUTE_DEFINITION' THEN 90
              WHEN 'PRODUCT' THEN 100 WHEN 'CLIENT' THEN 200 WHEN 'ORDER_SHEET' THEN 300
              WHEN 'ORDER' THEN 310 WHEN 'ORDER_ITEM' THEN 320 WHEN 'QUOTE' THEN 400
              WHEN 'QUOTE_ITEM' THEN 410 WHEN 'PREPARATION' THEN 500
              WHEN 'PREPARATION_STOCK_MOVEMENT' THEN 510 WHEN 'ORDER_LOAD' THEN 600
              WHEN 'ORDER_HISTORY' THEN 700 WHEN 'ORDER_SHEET_HISTORY' THEN 710 ELSE 9999 END
            """;

    private final JdbcTemplate jdbcTemplate;

    public BootstrapSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SnapshotPage findPage(long snapshotCursor, int page, int size) {
        long total = jdbcTemplate.queryForObject(SNAPSHOT_CTE
                        + "SELECT COUNT(*) FROM snapshot_records WHERE entity_type IN (" + SUPPORTED_TYPES + ")",
                Long.class, snapshotCursor, snapshotCursor, snapshotCursor);
        int offset = Math.multiplyExact(page, size);
        List<SnapshotRecord> records = jdbcTemplate.query(SNAPSHOT_CTE
                        + "SELECT entity_type, entity_id, version, deleted, payload_json, updated_at "
                        + "FROM snapshot_records WHERE entity_type IN (" + SUPPORTED_TYPES + ") "
                        + "ORDER BY " + DEPENDENCY_ORDER + ", entity_id LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> new SnapshotRecord(
                        resultSet.getString("entity_type"), resultSet.getString("entity_id"),
                        resultSet.getLong("version"), resultSet.getBoolean("deleted"),
                        resultSet.getString("payload_json"), resultSet.getTimestamp("updated_at").toInstant()),
                snapshotCursor, snapshotCursor, snapshotCursor, size, offset);
        return new SnapshotPage(records, (long) offset + records.size() < total);
    }

    public record SnapshotPage(List<SnapshotRecord> records, boolean hasMore) {
    }

    public record SnapshotRecord(String entityType, String entityId, long version, boolean deleted,
                                 String payloadJson, Instant updatedAt) {
    }
}
