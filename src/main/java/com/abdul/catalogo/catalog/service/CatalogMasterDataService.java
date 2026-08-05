package com.abdul.catalogo.catalog.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CatalogMasterDataService {
    public static final List<String> DEPENDENCY_ORDER = List.of(
            "COMPANY", "BRAND", "CATEGORY", "BRAND_CATEGORY", "MEASUREMENT_UNIT",
            "CATEGORY_ATTRIBUTE", "CATEGORY_ATTRIBUTE_OPTION", "CATEGORY_ATTRIBUTE_UNIT");
    private static final Set<String> SUPPORTED = Set.copyOf(DEPENDENCY_ORDER);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CatalogMasterDataService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean supports(String entityType) {
        return SUPPORTED.contains(normalizeType(entityType));
    }

    public int dependencyRank(String entityType) {
        int index = DEPENDENCY_ORDER.indexOf(normalizeType(entityType));
        return index < 0 ? DEPENDENCY_ORDER.size() + 1 : index;
    }

    @Transactional
    public void project(String entityType, String entityId, JsonNode payload, boolean deleted) {
        String type = normalizeType(entityType);
        if (!SUPPORTED.contains(type)) return;
        if (entityId == null || entityId.isBlank()) {
            throw new BusinessRuleException("MASTER_ID_REQUIRED", "La entidad maestra no contiene identidad global.");
        }
        String table = table(type);
        if (deleted) {
            jdbcTemplate.update("UPDATE " + table + " SET estado = FALSE, deleted = TRUE, updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now()), entityId);
            return;
        }

        ObjectNode source = payloadObject(payload);
        LinkedHashMap<String, Object> values = switch (type) {
            case "COMPANY" -> company(entityId, source);
            case "BRAND" -> brand(entityId, source);
            case "CATEGORY" -> category(entityId, source);
            case "BRAND_CATEGORY" -> brandCategory(entityId, source);
            case "MEASUREMENT_UNIT" -> measurementUnit(entityId, source);
            case "CATEGORY_ATTRIBUTE" -> categoryAttribute(entityId, source);
            case "CATEGORY_ATTRIBUTE_OPTION" -> categoryAttributeOption(entityId, source);
            case "CATEGORY_ATTRIBUTE_UNIT" -> categoryAttributeUnit(entityId, source);
            default -> throw new IllegalStateException("Tipo maestro no soportado: " + type);
        };
        values.put("deleted", false);
        values.put("payload_json", write(source));
        upsert(table, values);
    }

    @Transactional
    public int backfillFromSyncRecords() {
        int projected = 0;
        for (String entityType : DEPENDENCY_ORDER) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT entity_id, payload_json, deleted FROM sync_records WHERE entity_type = ? ORDER BY updated_at, entity_id",
                    entityType);
            for (Map<String, Object> row : rows) {
                JsonNode payload = read(String.valueOf(row.get("payload_json")));
                project(entityType, String.valueOf(row.get("entity_id")), payload, booleanValue(row.get("deleted"), false));
                projected++;
            }
        }
        return projected;
    }

    public ProductClassification canonicalizeRequired(ObjectNode aggregate) {
        ProductClassification classification = resolveRequired(aggregate);
        aggregate.put("companyId", classification.companyId());
        aggregate.put("company", classification.company());
        aggregate.put("brandId", classification.brandId());
        aggregate.put("brand", classification.brand());
        aggregate.put("categoryId", classification.categoryId());
        aggregate.put("category", classification.category());
        aggregate.put("subcategoryId", classification.subcategoryId());
        aggregate.put("subcategory", classification.subcategory());
        return classification;
    }

    public boolean canonicalizeIfResolvable(ObjectNode aggregate) {
        try {
            canonicalizeRequired(aggregate);
            return true;
        } catch (BusinessRuleException ignored) {
            return false;
        }
    }

    public Optional<ProductClassification> resolveOptional(JsonNode aggregate) {
        try {
            return Optional.of(resolveRequired(aggregate));
        } catch (BusinessRuleException ignored) {
            return Optional.empty();
        }
    }

    public ProductClassification resolveRequired(JsonNode aggregate) {
        MasterRef company = companyRef(text(aggregate, "companyId", "empresaId", "empresa_id"),
                text(aggregate, "company", "empresa"));
        MasterRef brand = brandRef(text(aggregate, "brandId", "marcaId", "marca_id"),
                text(aggregate, "brand", "marca"), company.id());
        MasterRef category = categoryRef(text(aggregate, "categoryId", "categoriaId", "categoria_id"),
                text(aggregate, "category", "categoria"), null, "categoría");

        String subcategoryId = text(aggregate, "subcategoryId", "subcategoriaId", "subcategoria_id");
        String subcategoryName = text(aggregate, "subcategory", "subcategoria");
        MasterRef subcategory = null;
        if (!subcategoryId.isBlank() || !subcategoryName.isBlank()) {
            subcategory = categoryRef(subcategoryId, subcategoryName, category.id(), "subcategoría");
        }

        Integer relation = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marca_categorias WHERE marca_id = ? AND categoria_id = ? AND estado = TRUE AND deleted = FALSE",
                Integer.class, brand.id(), category.id());
        if (relation == null || relation == 0) {
            throw new BusinessRuleException("BRAND_CATEGORY_NOT_FOUND",
                    "La marca " + brand.name() + " no está relacionada con la categoría " + category.name() + ".");
        }

        return new ProductClassification(company.id(), company.name(), brand.id(), brand.name(),
                category.id(), category.name(), subcategory == null ? "" : subcategory.id(),
                subcategory == null ? "" : subcategory.name());
    }

    public MasterSummary summary() {
        long companies = activeCount("empresas");
        long brands = activeCount("marcas");
        long categories = activeCount("categorias");
        long relations = activeCount("marca_categorias");
        long units = activeCount("unidades_medida");
        long attributes = activeCount("categoria_atributos");
        return new MasterSummary(companies, brands, categories, relations, units, attributes,
                companies > 0 && brands > 0 && categories > 0 && relations > 0);
    }

    public List<Map<String, Object>> companiesForTemplate() {
        return jdbcTemplate.queryForList("""
                SELECT id AS EmpresaId, nombre AS Empresa, ruc AS RUC
                FROM empresas WHERE estado = TRUE AND deleted = FALSE ORDER BY nombre
                """);
    }

    public List<Map<String, Object>> brandsForTemplate() {
        return jdbcTemplate.queryForList("""
                SELECT m.id AS MarcaId, m.empresa_id AS EmpresaId, e.nombre AS Empresa, m.nombre AS Marca
                FROM marcas m INNER JOIN empresas e ON e.id = m.empresa_id
                WHERE m.estado = TRUE AND m.deleted = FALSE AND e.estado = TRUE AND e.deleted = FALSE
                ORDER BY e.nombre, m.nombre
                """);
    }

    public List<Map<String, Object>> categoriesForTemplate() {
        return jdbcTemplate.queryForList("""
                SELECT c.id AS CategoriaId, c.categoria_padre_id AS CategoriaPadreId,
                       p.nombre AS CategoriaPadre, c.nombre AS Categoria,
                       CASE WHEN c.categoria_padre_id IS NULL THEN 'CATEGORIA' ELSE 'SUBCATEGORIA' END AS Nivel
                FROM categorias c LEFT JOIN categorias p ON p.id = c.categoria_padre_id
                WHERE c.estado = TRUE AND c.deleted = FALSE
                ORDER BY COALESCE(p.nombre, c.nombre), c.categoria_padre_id, c.nombre
                """);
    }

    public List<Map<String, Object>> unitsForTemplate() {
        return jdbcTemplate.queryForList("""
                SELECT id AS UnidadId, codigo AS Codigo, nombre AS Unidad, simbolo AS Simbolo, magnitud AS Magnitud
                FROM unidades_medida WHERE estado = TRUE AND deleted = FALSE ORDER BY magnitud, nombre
                """);
    }

    public List<Map<String, Object>> attributesForTemplate() {
        return jdbcTemplate.queryForList("""
                SELECT a.id AS AtributoId, a.categoria_id AS CategoriaId, c.nombre AS Categoria,
                       a.nombre AS Atributo, a.clave AS Clave, a.tipo_dato AS TipoDato,
                       a.nivel_captura AS NivelCaptura, a.requerido_activar AS Requerido,
                       a.filtrable AS Filtrable, a.puede_ser_eje AS PuedeSerEje
                FROM categoria_atributos a INNER JOIN categorias c ON c.id = a.categoria_id
                WHERE a.estado = TRUE AND a.deleted = FALSE AND c.estado = TRUE AND c.deleted = FALSE
                ORDER BY c.nombre, a.orden, a.nombre
                """);
    }

    private LinkedHashMap<String, Object> company(String id, ObjectNode source) {
        String name = requiredText(source, "Empresa", "nombre", "name");
        return values(id,
                "nombre", name,
                "nombre_normalizado", normalizeName(name),
                "ruc", text(source, "ruc"),
                "telefono", text(source, "telefono", "phone"),
                "direccion", text(source, "direccion", "address"),
                "estado", state(source));
    }

    private LinkedHashMap<String, Object> brand(String id, ObjectNode source) {
        String companyId = requiredText(source, "EmpresaId", "empresa_id", "empresaId", "companyId", "company_id");
        requireActive("empresas", companyId, "La empresa de la marca no existe o está inactiva.");
        String name = requiredText(source, "Marca", "nombre", "name");
        return values(id,
                "empresa_id", companyId,
                "nombre", name,
                "nombre_normalizado", normalizeName(name),
                "estado", state(source));
    }

    private LinkedHashMap<String, Object> category(String id, ObjectNode source) {
        String parentId = text(source, "categoria_padre_id", "categoriaPadreId", "parentCategoryId", "parent_category_id");
        if (!parentId.isBlank()) requireActive("categorias", parentId, "La categoría superior no existe o está inactiva.");
        String name = requiredText(source, "Categoría", "nombre", "name");
        return values(id,
                "categoria_padre_id", blankToNull(parentId),
                "nombre", name,
                "nombre_normalizado", normalizeName(name),
                "descripcion", text(source, "descripcion", "description"),
                "estado", state(source));
    }

    private LinkedHashMap<String, Object> brandCategory(String id, ObjectNode source) {
        String brandId = requiredText(source, "MarcaId", "marca_id", "marcaId", "brandId", "brand_id");
        String categoryId = requiredText(source, "CategoriaId", "categoria_id", "categoriaId", "categoryId", "category_id");
        requireActive("marcas", brandId, "La marca de la relación no existe o está inactiva.");
        requireActive("categorias", categoryId, "La categoría de la relación no existe o está inactiva.");
        return values(id, "marca_id", brandId, "categoria_id", categoryId, "estado", state(source));
    }

    private LinkedHashMap<String, Object> measurementUnit(String id, ObjectNode source) {
        return values(id,
                "codigo", requiredText(source, "Código de unidad", "codigo", "code"),
                "nombre", requiredText(source, "Unidad", "nombre", "name"),
                "simbolo", requiredText(source, "Símbolo", "simbolo", "symbol"),
                "magnitud", requiredText(source, "Magnitud", "magnitud", "magnitude"),
                "factor_a_base", decimal(source, BigDecimal.ONE, "factor_a_base", "factorABase", "baseFactor"),
                "decimales", integer(source, 3, "decimales", "decimals"),
                "estado", state(source));
    }

    private LinkedHashMap<String, Object> categoryAttribute(String id, ObjectNode source) {
        String categoryId = requiredText(source, "CategoriaId", "categoria_id", "categoriaId", "categoryId", "category_id");
        requireActive("categorias", categoryId, "La categoría del atributo no existe o está inactiva.");
        String name = requiredText(source, "Atributo", "nombre", "name");
        return values(id,
                "categoria_id", categoryId,
                "nombre", name,
                "clave", defaultText(text(source, "clave", "key"), normalizeKey(name)),
                "ayuda", blankToNull(text(source, "ayuda", "help")),
                "tipo_dato", defaultText(text(source, "tipo_dato", "tipoDato", "dataType"), "texto_corto"),
                "nivel_captura", defaultText(text(source, "nivel_captura", "nivelCaptura", "captureLevel"), "familia"),
                "requerido_activar", booleanField(source, false, "requerido_activar", "requeridoActivar", "requiredToActivate"),
                "visible_ficha", booleanField(source, true, "visible_ficha", "visibleFicha", "visibleOnCard"),
                "filtrable", booleanField(source, false, "filtrable", "filterable"),
                "puede_ser_eje", booleanField(source, false, "puede_ser_eje", "puedeSerEje", "canBeAxis"),
                "activo_nuevos", booleanField(source, true, "activo_nuevos", "activoNuevos", "activeForNew"),
                "longitud_maxima", nullableInteger(source, "longitud_maxima", "longitudMaxima", "maxLength"),
                "ejemplo", blankToNull(text(source, "ejemplo", "example")),
                "minimo", nullableDecimal(source, "minimo", "minimum"),
                "maximo", nullableDecimal(source, "maximo", "maximum"),
                "decimales", integer(source, 0, "decimales", "decimals"),
                "magnitud", blankToNull(text(source, "magnitud", "magnitude")),
                "maximo_selecciones", nullableInteger(source, "maximo_selecciones", "maximoSelecciones", "maxSelections"),
                "etiqueta_verdadero", blankToNull(text(source, "etiqueta_verdadero", "etiquetaVerdadero", "trueLabel")),
                "etiqueta_falso", blankToNull(text(source, "etiqueta_falso", "etiquetaFalso", "falseLabel")),
                "orden", integer(source, 0, "orden", "order"),
                "estado", state(source));
    }

    private LinkedHashMap<String, Object> categoryAttributeOption(String id, ObjectNode source) {
        String attributeId = requiredText(source, "AtributoId", "categoria_atributo_id", "categoriaAtributoId", "categoryAttributeId");
        requireActive("categoria_atributos", attributeId, "El atributo de la opción no existe o está inactivo.");
        return values(id,
                "categoria_atributo_id", attributeId,
                "etiqueta", requiredText(source, "Etiqueta", "etiqueta", "label"),
                "codigo", requiredText(source, "Código", "codigo", "code"),
                "orden", integer(source, 0, "orden", "order"),
                "estado", state(source));
    }

    private LinkedHashMap<String, Object> categoryAttributeUnit(String id, ObjectNode source) {
        String attributeId = requiredText(source, "AtributoId", "categoria_atributo_id", "categoriaAtributoId", "categoryAttributeId");
        String unitId = requiredText(source, "UnidadId", "unidad_medida_id", "unidadMedidaId", "measurementUnitId");
        requireActive("categoria_atributos", attributeId, "El atributo de la unidad no existe o está inactivo.");
        requireActive("unidades_medida", unitId, "La unidad de medida no existe o está inactiva.");
        return values(id,
                "categoria_atributo_id", attributeId,
                "unidad_medida_id", unitId,
                "es_predeterminada", booleanField(source, false, "es_predeterminada", "esPredeterminada", "isDefault"),
                "orden", integer(source, 0, "orden", "order"),
                "estado", state(source));
    }

    private MasterRef companyRef(String id, String name) {
        return singleReference("empresas", id, name, null, "empresa");
    }

    private MasterRef brandRef(String id, String name, String companyId) {
        return singleReference("marcas", id, name, companyId, "marca");
    }

    private MasterRef categoryRef(String id, String name, String parentId, String label) {
        return singleReference("categorias", id, name, parentId, label);
    }

    private MasterRef singleReference(String table, String id, String name, String parentId, String label) {
        List<Map<String, Object>> rows;
        if (!id.isBlank()) {
            rows = jdbcTemplate.queryForList("SELECT id, nombre, " + parentColumn(table)
                    + " AS parent_id FROM " + table + " WHERE id = ? AND estado = TRUE AND deleted = FALSE", id);
            if (rows.isEmpty()) {
                throw new BusinessRuleException("MASTER_REFERENCE_NOT_FOUND",
                        "La " + label + " indicada no existe o está inactiva: " + id + ".");
            }
            String canonicalName = String.valueOf(rows.get(0).get("nombre"));
            if (!name.isBlank() && !normalizeName(name).equals(normalizeName(canonicalName))) {
                throw new BusinessRuleException("MASTER_REFERENCE_NAME_MISMATCH",
                        "El nombre de la " + label + " no coincide con su identificador.");
            }
        } else {
            if (name.isBlank()) {
                throw new BusinessRuleException("MASTER_REFERENCE_REQUIRED", "Selecciona la " + label + ".");
            }
            String sql = "SELECT id, nombre, " + parentColumn(table) + " AS parent_id FROM " + table
                    + " WHERE nombre_normalizado = ? AND estado = TRUE AND deleted = FALSE";
            List<Object> args = new ArrayList<>();
            args.add(normalizeName(name));
            if (parentId != null) {
                sql += " AND " + parentColumn(table) + " = ?";
                args.add(parentId);
            } else if (table.equals("categorias")) {
                sql += " AND categoria_padre_id IS NULL";
            }
            rows = jdbcTemplate.queryForList(sql, args.toArray());
            if (rows.size() != 1) {
                throw new BusinessRuleException("MASTER_REFERENCE_NOT_FOUND",
                        "No se pudo resolver una única " + label + " activa con el nombre " + name + ".");
            }
        }
        Map<String, Object> row = rows.get(0);
        String resolvedParent = row.get("parent_id") == null ? "" : String.valueOf(row.get("parent_id"));
        if (parentId != null && !parentId.equals(resolvedParent)) {
            throw new BusinessRuleException("INVALID_MASTER_HIERARCHY",
                    "La " + label + " no pertenece a la clasificación seleccionada.");
        }
        return new MasterRef(String.valueOf(row.get("id")), String.valueOf(row.get("nombre")), resolvedParent);
    }

    private String parentColumn(String table) {
        return switch (table) {
            case "marcas" -> "empresa_id";
            case "categorias" -> "categoria_padre_id";
            default -> "NULL";
        };
    }

    private void requireActive(String table, String id, String message) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND estado = TRUE AND deleted = FALSE",
                Integer.class, id);
        if (count == null || count == 0) throw new BusinessRuleException("MASTER_REFERENCE_NOT_FOUND", message);
    }

    private long activeCount(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE estado = TRUE AND deleted = FALSE", Long.class);
        return count == null ? 0 : count;
    }

    private void upsert(String table, LinkedHashMap<String, Object> values) {
        Instant now = Instant.now();
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM " + table + " WHERE id = ?", Boolean.class, values.get("id")));
        if (exists) {
            values.put("updated_at", Timestamp.from(now));
            List<String> columns = values.keySet().stream()
                    .filter(column -> !column.equals("id") && !column.equals("created_at"))
                    .toList();
            String assignments = columns.stream().map(column -> column + " = ?").collect(Collectors.joining(", "));
            List<Object> args = columns.stream().map(values::get).collect(Collectors.toCollection(ArrayList::new));
            args.add(values.get("id"));
            jdbcTemplate.update("UPDATE " + table + " SET " + assignments + " WHERE id = ?", args.toArray());
        } else {
            values.put("created_at", Timestamp.from(now));
            values.put("updated_at", Timestamp.from(now));
            String columns = String.join(", ", values.keySet());
            String placeholders = values.keySet().stream().map(ignored -> "?").collect(Collectors.joining(", "));
            jdbcTemplate.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")",
                    values.values().toArray());
        }
    }

    private LinkedHashMap<String, Object> values(String id, Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private ObjectNode payloadObject(JsonNode payload) {
        if (payload instanceof ObjectNode object) {
            JsonNode data = object.get("data");
            return data instanceof ObjectNode nested ? nested.deepCopy() : object.deepCopy();
        }
        throw new BusinessRuleException("INVALID_MASTER_PAYLOAD", "El dato maestro debe ser un objeto JSON.");
    }

    private String requiredText(JsonNode node, String label, String... keys) {
        String value = text(node, keys);
        if (value.isBlank()) {
            throw new BusinessRuleException("MASTER_FIELD_REQUIRED", label + " es obligatorio.");
        }
        return value;
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) return value.asText("").trim();
        }
        return "";
    }

    private boolean state(JsonNode node) {
        return booleanField(node, true, "estado", "active", "activo", "status");
    }

    private boolean booleanField(JsonNode node, boolean fallback, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            if (value.isBoolean()) return value.asBoolean();
            if (value.isNumber()) return value.asInt() != 0;
            String text = value.asText("").trim().toLowerCase(Locale.ROOT);
            if (Set.of("1", "true", "si", "sí", "active", "activo").contains(text)) return true;
            if (Set.of("0", "false", "no", "inactive", "inactivo").contains(text)) return false;
        }
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean item) return item;
        if (value instanceof Number item) return item.intValue() != 0;
        if (value == null) return fallback;
        return Boolean.parseBoolean(value.toString());
    }

    private BigDecimal decimal(JsonNode node, BigDecimal fallback, String... keys) {
        BigDecimal value = nullableDecimal(node, keys);
        return value == null ? fallback : value;
    }

    private BigDecimal nullableDecimal(JsonNode node, String... keys) {
        String value = text(node, keys).replace(',', '.');
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new BusinessRuleException("INVALID_MASTER_NUMBER", "Un valor numérico del dato maestro no es válido.");
        }
    }

    private int integer(JsonNode node, int fallback, String... keys) {
        Integer value = nullableInteger(node, keys);
        return value == null ? fallback : value;
    }

    private Integer nullableInteger(JsonNode node, String... keys) {
        String value = text(node, keys);
        if (value.isBlank()) return null;
        try {
            return Integer.valueOf(value.replace(".0", ""));
        } catch (NumberFormatException exception) {
            throw new BusinessRuleException("INVALID_MASTER_NUMBER", "Un valor entero del dato maestro no es válido.");
        }
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeName(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return normalizeName(value).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String table(String entityType) {
        return switch (entityType) {
            case "COMPANY" -> "empresas";
            case "BRAND" -> "marcas";
            case "CATEGORY" -> "categorias";
            case "BRAND_CATEGORY" -> "marca_categorias";
            case "MEASUREMENT_UNIT" -> "unidades_medida";
            case "CATEGORY_ATTRIBUTE" -> "categoria_atributos";
            case "CATEGORY_ATTRIBUTE_OPTION" -> "categoria_atributo_opciones";
            case "CATEGORY_ATTRIBUTE_UNIT" -> "categoria_atributo_unidades";
            default -> throw new IllegalArgumentException("Tipo maestro no soportado: " + entityType);
        };
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_MASTER_PAYLOAD", "No se pudo serializar el dato maestro.");
        }
    }

    private JsonNode read(String json) {
        try {
            JsonNode result = objectMapper.readTree(json);
            return result == null ? objectMapper.createObjectNode() : result;
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_MASTER_PAYLOAD", "Un dato maestro sincronizado contiene JSON inválido.");
        }
    }

    private record MasterRef(String id, String name, String parentId) {
    }

    public record ProductClassification(String companyId, String company, String brandId, String brand,
                                        String categoryId, String category, String subcategoryId,
                                        String subcategory) {
    }

    public record MasterSummary(long companies, long brands, long categories, long brandCategoryRelations,
                                long measurementUnits, long categoryAttributes, boolean readyForProductImport) {
    }
}
