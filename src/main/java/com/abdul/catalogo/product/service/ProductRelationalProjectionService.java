package com.abdul.catalogo.product.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class ProductRelationalProjectionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProductRelationalProjectionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void replace(String productId, JsonNode aggregate) {
        clear(productId);
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);

        projectVariants(productId, aggregate.path("variants"), timestamp);
        projectFamilyAxes(productId, aggregate.path("familyAxes"), timestamp);
        projectAttributeValues(productId, aggregate.path("attributeValues"), timestamp);
        projectAttributeOptions(productId, aggregate.path("attributeOptions"), timestamp);
        projectPresentations(productId, aggregate.path("presentations"), timestamp);
        projectPrices(productId, aggregate.path("prices"), timestamp);
        projectImages(productId, aggregate.path("images"), timestamp);
    }

    @Transactional
    public void clear(String productId) {
        jdbcTemplate.update("DELETE FROM producto_atributo_opciones WHERE producto_id = ?", productId);
        jdbcTemplate.update("DELETE FROM producto_precios WHERE producto_id = ?", productId);
        jdbcTemplate.update("DELETE FROM producto_imagenes WHERE producto_id = ?", productId);
        jdbcTemplate.update("DELETE FROM producto_presentaciones WHERE producto_id = ?", productId);
        jdbcTemplate.update("DELETE FROM producto_atributos WHERE producto_id = ?", productId);
        jdbcTemplate.update("DELETE FROM producto_familia_ejes WHERE producto_id = ?", productId);
        jdbcTemplate.update("DELETE FROM producto_variantes_catalogo WHERE producto_id = ?", productId);
    }

    private void projectVariants(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String sku = text(row, "sku");
            String id = text(row, "id");
            if (id.isBlank()) id = stableId("variant", productId, sku, Integer.toString(index));
            jdbcTemplate.update("""
                    INSERT INTO producto_variantes_catalogo(
                        id, producto_id, sku, codigo_proveedor, nombre_corto, estado,
                        atributos_json, payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    sku,
                    text(row, "supplierCode", "codigo_proveedor"),
                    text(row, "shortName", "nombre_corto"),
                    valueOr(row, "status", "ACTIVE"),
                    json(row.path("attributes")),
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private void projectFamilyAxes(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String attributeId = text(row, "categoria_atributo_id", "categoryAttributeId");
            if (attributeId.isBlank()) continue;
            String id = stableId("axis", productId, attributeId);
            jdbcTemplate.update("""
                    INSERT INTO producto_familia_ejes(
                        id, producto_id, categoria_atributo_id, orden,
                        payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    attributeId,
                    integer(row, index, "orden", "order"),
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private void projectAttributeValues(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String attributeDefinitionId = text(row, "categoria_atributo_id", "categoryAttributeId");
            if (attributeDefinitionId.isBlank()) continue;
            String variantId = nullableText(row, "variante_id", "variantId");
            String id = text(row, "id");
            if (id.isBlank()) {
                id = stableId("attribute", productId, attributeDefinitionId,
                        variantId == null ? "family" : variantId, Integer.toString(index));
            }
            jdbcTemplate.update("""
                    INSERT INTO producto_atributos(
                        id, producto_id, variante_id, categoria_atributo_id,
                        valor_texto, valor_numero, valor_booleano, valor_fecha,
                        valor_normalizado, valor_maximo, unidad_medida_id,
                        categoria_atributo_unidad_id, payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    variantId,
                    attributeDefinitionId,
                    nullableText(row, "valor_texto", "textValue", "valor"),
                    decimal(row, "valor_numero", "numberValue"),
                    bool(row, "valor_booleano", "booleanValue"),
                    nullableText(row, "valor_fecha", "dateValue"),
                    nullableText(row, "valor_normalizado", "normalizedValue"),
                    decimal(row, "valor_maximo", "maximumValue"),
                    nullableText(row, "unidad_medida_id", "measurementUnitId"),
                    nullableText(row, "categoria_atributo_unidad_id", "categoryAttributeUnitId"),
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private void projectAttributeOptions(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String attributeId = text(row, "producto_atributo_id", "productAttributeId");
            String optionId = text(row, "opcion_id", "optionId");
            if (attributeId.isBlank() || optionId.isBlank()) continue;
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM producto_atributos WHERE id = ? AND producto_id = ?",
                    Integer.class,
                    attributeId,
                    productId);
            if (existing == null || existing == 0) continue;
            String id = stableId("attribute-option", productId, attributeId, optionId, Integer.toString(index));
            jdbcTemplate.update("""
                    INSERT INTO producto_atributo_opciones(
                        id, producto_id, producto_atributo_id, opcion_id,
                        payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    attributeId,
                    optionId,
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private void projectPresentations(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String name = valueOr(row, "name", valueOr(row, "nombre", "Unidad"));
            String sku = text(row, "sku");
            String id = text(row, "id", "presentationId", "presentacion_id");
            if (id.isBlank()) id = stableId("presentation", productId, sku, name, Integer.toString(index));
            jdbcTemplate.update("""
                    INSERT INTO producto_presentaciones(
                        id, producto_id, sku, nombre, equivalencia, unidad_base,
                        venta_minima, estado, payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    sku,
                    name,
                    decimalOr(row, BigDecimal.ONE, "equivalence", "equivalencia"),
                    valueOr(row, "baseUnit", valueOr(row, "unidad", "UND")),
                    decimalOr(row, BigDecimal.ONE, "minimumSale", "venta_minima"),
                    valueOr(row, "status", "ACTIVE"),
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private void projectPrices(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String sku = text(row, "sku");
            String priceList = valueOr(row, "priceList", valueOr(row, "lista_precio_nombre", "General"));
            String presentation = valueOr(row, "presentation", valueOr(row, "presentacion", "Unidad"));
            String id = stableId("price", productId, sku, priceList, presentation, Integer.toString(index));
            jdbcTemplate.update("""
                    INSERT INTO producto_precios(
                        id, producto_id, sku, lista_precio, lista_precio_id,
                        presentacion, presentacion_id, moneda, impuesto, precio,
                        requiere_cotizacion, configuracion, payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    sku,
                    priceList,
                    text(row, "priceListId", "lista_precio_id"),
                    presentation,
                    text(row, "presentationId", "presentacion_id"),
                    valueOr(row, "currency", valueOr(row, "moneda", "PEN")),
                    decimalOr(row, BigDecimal.valueOf(18), "taxRate", "impuesto"),
                    decimal(row, "price", "valor"),
                    boolOr(row, false, "quoteRequired", "requiere_cotizacion"),
                    valueOr(row, "configuration", valueOr(row, "configuracion", "precio_fijo")),
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private void projectImages(String productId, JsonNode rows, Timestamp now) {
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            String storageKey = text(row, "storageKey", "storage_key");
            if (storageKey.isBlank()) continue;
            String id = storageFileId(storageKey);
            if (id.isBlank()) id = stableId("image", productId, storageKey, Integer.toString(index));
            jdbcTemplate.update("""
                    INSERT INTO producto_imagenes(
                        id, producto_id, sku, storage_key, tipo, principal,
                        payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    productId,
                    text(row, "sku"),
                    storageKey,
                    valueOr(row, "type", "PRODUCT"),
                    boolOr(row, false, "primary", "principal"),
                    json(row),
                    now,
                    now);
            index++;
        }
    }

    private String storageFileId(String storageKey) {
        String[] parts = storageKey.replace('\\', '/').split("/");
        return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : "";
    }

    private String stableId(String namespace, String... values) {
        StringBuilder value = new StringBuilder(namespace);
        for (String item : values) value.append(':').append(item == null ? "" : item);
        return UUID.nameUUIDFromBytes(value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String text(JsonNode node, String... names) {
        String value = nullableText(node, names);
        return value == null ? "" : value;
    }

    private String nullableText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText().trim();
                if (!text.isBlank()) return text;
            }
        }
        return null;
    }

    private String valueOr(JsonNode node, String name, String fallback) {
        String value = nullableText(node, name);
        return value == null ? fallback : value;
    }

    private int integer(JsonNode node, int fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) return value.asInt();
        }
        return fallback;
    }

    private BigDecimal decimal(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) return value.decimalValue();
            try {
                String raw = value.asText().trim();
                if (!raw.isBlank()) return new BigDecimal(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal decimalOr(JsonNode node, BigDecimal fallback, String... names) {
        BigDecimal value = decimal(node, names);
        return value == null ? fallback : value;
    }

    private Boolean bool(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isBoolean()) return value.asBoolean();
            String raw = value.asText().trim();
            if (raw.equalsIgnoreCase("true") || raw.equals("1")) return true;
            if (raw.equalsIgnoreCase("false") || raw.equals("0")) return false;
        }
        return null;
    }

    private boolean boolOr(JsonNode node, boolean fallback, String... names) {
        Boolean value = bool(node, names);
        return value == null ? fallback : value;
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null || node.isMissingNode()
                    ? objectMapper.createObjectNode() : node);
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_JSON", "No se pudo proyectar el producto en MySQL.");
        }
    }
}
