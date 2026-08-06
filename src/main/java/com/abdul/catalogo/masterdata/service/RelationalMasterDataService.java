package com.abdul.catalogo.masterdata.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RelationalMasterDataService {
    public static final Set<String> ENTITY_TYPES = Set.of(
            "COMPANY", "BRAND", "CATEGORY", "BRAND_CATEGORY", "MEASUREMENT_UNIT",
            "CATEGORY_ATTRIBUTE", "CATEGORY_ATTRIBUTE_OPTION", "CATEGORY_ATTRIBUTE_UNIT",
            "LEGACY_ATTRIBUTE_DEFINITION", "PRICE_LIST");

    private static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("COMPANY", "empresas"),
            Map.entry("BRAND", "marcas"),
            Map.entry("CATEGORY", "categorias"),
            Map.entry("BRAND_CATEGORY", "marca_categorias"),
            Map.entry("MEASUREMENT_UNIT", "unidades_medida"),
            Map.entry("CATEGORY_ATTRIBUTE", "categoria_atributos"),
            Map.entry("CATEGORY_ATTRIBUTE_OPTION", "categoria_atributo_opciones"),
            Map.entry("CATEGORY_ATTRIBUTE_UNIT", "categoria_atributo_unidades"),
            Map.entry("LEGACY_ATTRIBUTE_DEFINITION", "atributos_def"),
            Map.entry("PRICE_LIST", "listas_precios"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RelationalMasterDataService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean supports(String entityType) {
        return ENTITY_TYPES.contains(normalizeType(entityType));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(String entityType, String entityId, JsonNode payload,
                      long version, String originDeviceId, boolean deleted) {
        String type = normalizeType(entityType);
        if (!supports(type)) return;
        if (deleted) {
            jdbc.update("UPDATE " + TABLES.get(type)
                            + " SET deleted = TRUE, version = ?, origin_device_id = ?, updated_at = ? WHERE id = ?",
                    version, originDeviceId, Instant.now(), entityId);
            return;
        }
        switch (type) {
            case "COMPANY" -> upsertCompany(entityId, payload, version, originDeviceId);
            case "BRAND" -> upsertBrand(entityId, payload, version, originDeviceId);
            case "CATEGORY" -> upsertCategory(entityId, payload, version, originDeviceId);
            case "BRAND_CATEGORY" -> upsertBrandCategory(entityId, payload, version, originDeviceId);
            case "MEASUREMENT_UNIT" -> upsertMeasurementUnit(entityId, payload, version, originDeviceId);
            case "CATEGORY_ATTRIBUTE" -> upsertCategoryAttribute(entityId, payload, version, originDeviceId);
            case "CATEGORY_ATTRIBUTE_OPTION" -> upsertCategoryAttributeOption(entityId, payload, version, originDeviceId);
            case "CATEGORY_ATTRIBUTE_UNIT" -> upsertCategoryAttributeUnit(entityId, payload, version, originDeviceId);
            case "LEGACY_ATTRIBUTE_DEFINITION" -> upsertLegacyAttribute(entityId, payload, version, originDeviceId);
            case "PRICE_LIST" -> upsertPriceList(entityId, payload, version, originDeviceId);
            default -> throw new IllegalArgumentException("Tipo maestro no soportado: " + type);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateLastSequence(String entityType, String entityId, long sequence) {
        String type = normalizeType(entityType);
        if (!supports(type)) return;
        jdbc.update("UPDATE " + TABLES.get(type) + " SET last_sequence = ? WHERE id = ?", sequence, entityId);
    }

    public Optional<String> currentPayload(String entityType, String entityId) {
        return jdbc.query("SELECT payload_json FROM sync_master_payloads WHERE entity_type = ? AND entity_id = ?",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(),
                normalizeType(entityType), entityId);
    }

    public Optional<String> findCompanyId(String name) {
        return one("SELECT id FROM empresas WHERE nombre_normalizado = ? AND deleted = FALSE", normalize(name));
    }

    public Optional<String> findBrandId(String companyId, String name) {
        return one("SELECT id FROM marcas WHERE empresa_id = ? AND nombre_normalizado = ? AND deleted = FALSE",
                companyId, normalize(name));
    }

    public Optional<String> findRootCategoryId(String name) {
        return one("SELECT id FROM categorias WHERE categoria_padre_id IS NULL AND nombre_normalizado = ? AND deleted = FALSE",
                normalize(name));
    }

    public Optional<String> findChildCategoryId(String parentId, String name) {
        return one("SELECT id FROM categorias WHERE categoria_padre_id = ? AND nombre_normalizado = ? AND deleted = FALSE",
                parentId, normalize(name));
    }

    public Optional<String> findCategoryByPath(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        String[] parts = path.split("\\s*>\\s*|\\s*/\\s*");
        Optional<String> current = Optional.empty();
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            current = current.isEmpty() ? findRootCategoryId(part) : findChildCategoryId(current.get(), part);
            if (current.isEmpty()) return Optional.empty();
        }
        return current;
    }

    public Optional<String> findMeasurementUnitId(String code) {
        return one("SELECT id FROM unidades_medida WHERE LOWER(codigo) = ? AND deleted = FALSE",
                code == null ? "" : code.trim().toLowerCase(Locale.ROOT));
    }

    public Optional<String> findCategoryAttributeId(String categoryId, String name) {
        return one("SELECT id FROM categoria_atributos WHERE categoria_id = ? AND LOWER(nombre) = ? AND deleted = FALSE",
                categoryId, normalize(name));
    }

    public Optional<String> findPriceListId(String name) {
        return one("SELECT id FROM listas_precios WHERE nombre_normalizado = ? AND deleted = FALSE", normalize(name));
    }

    public boolean brandAppliesToCategory(String brandId, String categoryId) {
        Integer count = jdbc.queryForObject("""
                WITH RECURSIVE ancestors AS (
                    SELECT id, categoria_padre_id FROM categorias WHERE id = ? AND deleted = FALSE
                    UNION ALL
                    SELECT parent.id, parent.categoria_padre_id
                    FROM categorias parent
                    INNER JOIN ancestors child ON child.categoria_padre_id = parent.id
                    WHERE parent.deleted = FALSE
                )
                SELECT COUNT(*)
                FROM marca_categorias relation
                INNER JOIN ancestors category_tree ON category_tree.id = relation.categoria_id
                WHERE relation.marca_id = ? AND relation.estado = TRUE AND relation.deleted = FALSE
                """, Integer.class, categoryId, brandId);
        return count != null && count > 0;
    }

    public long countVisibleRecords() {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM sync_master_payloads", Long.class);
        return result == null ? 0 : result;
    }

    public String normalize(String value) {
        if (value == null) return "";
        String ascii = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ");
        return ascii.toLowerCase(Locale.ROOT);
    }

    private void upsertCompany(String id, JsonNode p, long version, String origin) {
        String name = requiredText(p, "nombre", "name");
        jdbc.update("""
                INSERT INTO empresas(id, nombre, nombre_normalizado, ruc, telefono, direccion, estado,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE nombre = VALUES(nombre), nombre_normalizado = VALUES(nombre_normalizado),
                    ruc = VALUES(ruc), telefono = VALUES(telefono), direccion = VALUES(direccion),
                    estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, name, normalize(name), text(p, "ruc"), text(p, "telefono", "phone"),
                text(p, "direccion", "address"), bool(p, true, "estado", "active"), version, origin,
                Instant.now(), Instant.now());
    }

    private void upsertBrand(String id, JsonNode p, long version, String origin) {
        String companyId = requiredText(p, "empresa_id", "company_id", "companyId");
        String name = requiredText(p, "nombre", "name");
        jdbc.update("""
                INSERT INTO marcas(id, empresa_id, nombre, nombre_normalizado, estado,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE empresa_id = VALUES(empresa_id), nombre = VALUES(nombre),
                    nombre_normalizado = VALUES(nombre_normalizado), estado = VALUES(estado),
                    version = VALUES(version), deleted = FALSE, origin_device_id = VALUES(origin_device_id),
                    updated_at = VALUES(updated_at)
                """, id, companyId, name, normalize(name), bool(p, true, "estado", "active"),
                version, origin, Instant.now(), Instant.now());
    }

    private void upsertCategory(String id, JsonNode p, long version, String origin) {
        String parentId = nullableText(p, "categoria_padre_id", "parent_category_id", "parentCategoryId");
        String name = requiredText(p, "nombre", "name");
        jdbc.update("""
                INSERT INTO categorias(id, categoria_padre_id, nombre, nombre_normalizado, descripcion, estado,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE categoria_padre_id = VALUES(categoria_padre_id), nombre = VALUES(nombre),
                    nombre_normalizado = VALUES(nombre_normalizado), descripcion = VALUES(descripcion),
                    estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, parentId, name, normalize(name), text(p, "descripcion", "description"),
                bool(p, true, "estado", "active"), version, origin, Instant.now(), Instant.now());
    }

    private void upsertBrandCategory(String id, JsonNode p, long version, String origin) {
        jdbc.update("""
                INSERT INTO marca_categorias(id, marca_id, categoria_id, estado,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE marca_id = VALUES(marca_id), categoria_id = VALUES(categoria_id),
                    estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, requiredText(p, "marca_id", "brand_id", "brandId"),
                requiredText(p, "categoria_id", "category_id", "categoryId"),
                bool(p, true, "estado", "active"), version, origin, Instant.now(), Instant.now());
    }

    private void upsertMeasurementUnit(String id, JsonNode p, long version, String origin) {
        jdbc.update("""
                INSERT INTO unidades_medida(id, codigo, nombre, simbolo, magnitud, factor_a_base, decimales, estado,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE codigo = VALUES(codigo), nombre = VALUES(nombre), simbolo = VALUES(simbolo),
                    magnitud = VALUES(magnitud), factor_a_base = VALUES(factor_a_base), decimales = VALUES(decimales),
                    estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, requiredText(p, "codigo", "code"), requiredText(p, "nombre", "name"),
                requiredText(p, "simbolo", "symbol"), requiredText(p, "magnitud", "magnitude"),
                decimal(p, BigDecimal.ONE, "factor_a_base", "base_factor", "baseFactor"),
                integer(p, 3, "decimales", "decimals"), bool(p, true, "estado", "active"),
                version, origin, Instant.now(), Instant.now());
    }

    private void upsertCategoryAttribute(String id, JsonNode p, long version, String origin) {
        jdbc.update("""
                INSERT INTO categoria_atributos(id, categoria_id, nombre, clave, ayuda, tipo_dato, nivel_captura,
                    requerido_activar, visible_ficha, filtrable, puede_ser_eje, activo_nuevos, longitud_maxima,
                    ejemplo, minimo, maximo, decimales, magnitud, maximo_selecciones, etiqueta_verdadero,
                    etiqueta_falso, orden, estado, version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE categoria_id = VALUES(categoria_id), nombre = VALUES(nombre),
                    clave = VALUES(clave), ayuda = VALUES(ayuda), tipo_dato = VALUES(tipo_dato),
                    nivel_captura = VALUES(nivel_captura), requerido_activar = VALUES(requerido_activar),
                    visible_ficha = VALUES(visible_ficha), filtrable = VALUES(filtrable),
                    puede_ser_eje = VALUES(puede_ser_eje), activo_nuevos = VALUES(activo_nuevos),
                    longitud_maxima = VALUES(longitud_maxima), ejemplo = VALUES(ejemplo), minimo = VALUES(minimo),
                    maximo = VALUES(maximo), decimales = VALUES(decimales), magnitud = VALUES(magnitud),
                    maximo_selecciones = VALUES(maximo_selecciones), etiqueta_verdadero = VALUES(etiqueta_verdadero),
                    etiqueta_falso = VALUES(etiqueta_falso), orden = VALUES(orden), estado = VALUES(estado),
                    version = VALUES(version), deleted = FALSE, origin_device_id = VALUES(origin_device_id),
                    updated_at = VALUES(updated_at)
                """, id, requiredText(p, "categoria_id", "category_id", "categoryId"),
                requiredText(p, "nombre", "name"), requiredText(p, "clave", "key"),
                nullableText(p, "ayuda", "help"), requiredText(p, "tipo_dato", "data_type", "dataType"),
                defaultText(p, "familia", "nivel_captura", "capture_level", "captureLevel"),
                bool(p, false, "requerido_activar", "required_to_activate"),
                bool(p, true, "visible_ficha", "visible_on_card"), bool(p, false, "filtrable", "filterable"),
                bool(p, false, "puede_ser_eje", "can_be_axis"), bool(p, true, "activo_nuevos", "active_for_new"),
                nullableInteger(p, "longitud_maxima", "maximum_length"), nullableText(p, "ejemplo", "example"),
                nullableDecimal(p, "minimo", "minimum"), nullableDecimal(p, "maximo", "maximum"),
                integer(p, 0, "decimales", "decimals"), nullableText(p, "magnitud", "magnitude"),
                nullableInteger(p, "maximo_selecciones", "maximum_selections"),
                nullableText(p, "etiqueta_verdadero", "true_label"), nullableText(p, "etiqueta_falso", "false_label"),
                integer(p, 0, "orden", "sort_order"), bool(p, true, "estado", "active"),
                version, origin, Instant.now(), Instant.now());
    }

    private void upsertCategoryAttributeOption(String id, JsonNode p, long version, String origin) {
        jdbc.update("""
                INSERT INTO categoria_atributo_opciones(id, categoria_atributo_id, etiqueta, codigo, orden, estado,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE categoria_atributo_id = VALUES(categoria_atributo_id),
                    etiqueta = VALUES(etiqueta), codigo = VALUES(codigo), orden = VALUES(orden),
                    estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, requiredText(p, "categoria_atributo_id", "category_attribute_id", "categoryAttributeId"),
                requiredText(p, "etiqueta", "label"), requiredText(p, "codigo", "code"),
                integer(p, 0, "orden", "sort_order"), bool(p, true, "estado", "active"),
                version, origin, Instant.now(), Instant.now());
    }

    private void upsertCategoryAttributeUnit(String id, JsonNode p, long version, String origin) {
        jdbc.update("""
                INSERT INTO categoria_atributo_unidades(id, categoria_atributo_id, unidad_medida_id,
                    es_predeterminada, orden, estado, version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE categoria_atributo_id = VALUES(categoria_atributo_id),
                    unidad_medida_id = VALUES(unidad_medida_id), es_predeterminada = VALUES(es_predeterminada),
                    orden = VALUES(orden), estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, requiredText(p, "categoria_atributo_id", "category_attribute_id", "categoryAttributeId"),
                requiredText(p, "unidad_medida_id", "measurement_unit_id", "measurementUnitId"),
                bool(p, false, "es_predeterminada", "default"), integer(p, 0, "orden", "sort_order"),
                bool(p, true, "estado", "active"), version, origin, Instant.now(), Instant.now());
    }

    private void upsertLegacyAttribute(String id, JsonNode p, long version, String origin) {
        jdbc.update("""
                INSERT INTO atributos_def(id, categoria_id, nombre, tipo, es_variante,
                    version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE categoria_id = VALUES(categoria_id), nombre = VALUES(nombre),
                    tipo = VALUES(tipo), es_variante = VALUES(es_variante), version = VALUES(version),
                    deleted = FALSE, origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, requiredText(p, "categoria_id", "category_id", "categoryId"),
                requiredText(p, "nombre", "name"), requiredText(p, "tipo", "type"),
                bool(p, false, "es_variante", "is_variant"), version, origin, Instant.now(), Instant.now());
    }

    private void upsertPriceList(String id, JsonNode p, long version, String origin) {
        String name = requiredText(p, "nombre", "name");
        String currency = defaultText(p, "PEN", "moneda", "currency").toUpperCase(Locale.ROOT);
        if (!currency.equals("PEN")) {
            throw new BusinessRuleException("PRICE_LIST_CURRENCY", "Las listas de precios solo admiten moneda PEN.");
        }
        jdbc.update("""
                INSERT INTO listas_precios(id, nombre, nombre_normalizado, moneda, incluye_igv, igv_porcentaje,
                    estado, version, deleted, origin_device_id, created_at, updated_at)
                VALUES (?, ?, ?, 'PEN', ?, ?, ?, ?, FALSE, ?, ?, ?)
                ON DUPLICATE KEY UPDATE nombre = VALUES(nombre), nombre_normalizado = VALUES(nombre_normalizado),
                    moneda = 'PEN', incluye_igv = VALUES(incluye_igv), igv_porcentaje = VALUES(igv_porcentaje),
                    estado = VALUES(estado), version = VALUES(version), deleted = FALSE,
                    origin_device_id = VALUES(origin_device_id), updated_at = VALUES(updated_at)
                """, id, name, normalize(name), bool(p, true, "incluye_igv", "includes_tax"),
                decimal(p, BigDecimal.valueOf(18), "igv_porcentaje", "tax_rate", "taxRate"),
                bool(p, true, "estado", "active"), version, origin, Instant.now(), Instant.now());
    }

    private Optional<String> one(String sql, Object... args) {
        return jdbc.query(sql, rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(), args);
    }

    private String normalizeType(String entityType) {
        return entityType == null ? "" : entityType.trim().toUpperCase(Locale.ROOT);
    }

    private JsonNode value(JsonNode payload, String... names) {
        JsonNode source = payload != null && payload.path("data").isObject() ? payload.path("data") : payload;
        if (source == null) return null;
        for (String name : names) {
            JsonNode node = source.get(name);
            if (node != null && !node.isNull()) return node;
        }
        return null;
    }

    private String requiredText(JsonNode payload, String... names) {
        String result = nullableText(payload, names);
        if (result == null || result.isBlank()) {
            throw new BusinessRuleException("MASTER_REQUIRED_FIELD", "Falta el campo maestro obligatorio " + names[0] + ".");
        }
        return result;
    }

    private String text(JsonNode payload, String... names) {
        String result = nullableText(payload, names);
        return result == null ? "" : result;
    }

    private String defaultText(JsonNode payload, String fallback, String... names) {
        String result = nullableText(payload, names);
        return result == null || result.isBlank() ? fallback : result;
    }

    private String nullableText(JsonNode payload, String... names) {
        JsonNode node = value(payload, names);
        if (node == null) return null;
        String result = node.asText().trim();
        return result.isEmpty() ? null : result;
    }

    private boolean bool(JsonNode payload, boolean fallback, String... names) {
        JsonNode node = value(payload, names);
        if (node == null) return fallback;
        if (node.isBoolean()) return node.asBoolean();
        String value = node.asText().trim().toLowerCase(Locale.ROOT);
        return Set.of("1", "true", "si", "sí", "yes", "activo", "active").contains(value);
    }

    private int integer(JsonNode payload, int fallback, String... names) {
        Integer result = nullableInteger(payload, names);
        return result == null ? fallback : result;
    }

    private Integer nullableInteger(JsonNode payload, String... names) {
        JsonNode node = value(payload, names);
        if (node == null || node.asText().isBlank()) return null;
        return node.isNumber() ? node.asInt() : Integer.valueOf(node.asText().trim());
    }

    private BigDecimal decimal(JsonNode payload, BigDecimal fallback, String... names) {
        BigDecimal result = nullableDecimal(payload, names);
        return result == null ? fallback : result;
    }

    private BigDecimal nullableDecimal(JsonNode payload, String... names) {
        JsonNode node = value(payload, names);
        if (node == null || node.asText().isBlank()) return null;
        return new BigDecimal(node.asText().trim().replace(',', '.'));
    }

    public JsonNode readPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Payload maestro inválido en la base de datos.", exception);
        }
    }
}
