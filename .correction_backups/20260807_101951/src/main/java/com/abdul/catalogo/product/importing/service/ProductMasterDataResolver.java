package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.masterdata.service.BrandCategoryHierarchyService;
import com.abdul.catalogo.masterdata.service.RelationalMasterDataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ProductMasterDataResolver {
    private static final Set<String> TRUE_VALUES =
            Set.of("si", "sí", "1", "true", "yes", "activo");
    private static final Set<String> FALSE_VALUES =
            Set.of("no", "0", "false", "inactivo");

    private final RelationalMasterDataService masters;
    private final BrandCategoryHierarchyService hierarchy;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductMasterDataResolver(
            RelationalMasterDataService masters,
            BrandCategoryHierarchyService hierarchy,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.masters = masters;
        this.hierarchy = hierarchy;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ProductImportCandidate resolve(ProductImportCandidate candidate) {
        ObjectNode aggregate = candidate.aggregate().deepCopy();
        List<String> warnings = new ArrayList<>(candidate.warnings());
        List<String> errors = new ArrayList<>(candidate.errors());

        String companyName = aggregate.path("company").asText().trim();
        String companyId = aggregate.path("companyId").asText().trim();
        if (companyId.isBlank()) {
            companyId = masters.findCompanyId(companyName).orElse("");
            if (companyId.isBlank()) {
                errors.add("No existe la empresa " + companyName + " en los datos maestros.");
            } else {
                aggregate.put("companyId", companyId);
            }
        } else if (!matchesCompany(companyId, companyName)) {
            errors.add("EmpresaId no existe o no corresponde a " + companyName + ".");
        }

        String brandName = aggregate.path("brand").asText().trim();
        String brandId = aggregate.path("brandId").asText().trim();
        if (brandId.isBlank() && !companyId.isBlank()) {
            brandId = masters.findBrandId(companyId, brandName).orElse("");
            if (brandId.isBlank()) {
                errors.add("No existe la marca " + brandName + " dentro de " + companyName + ".");
            } else {
                aggregate.put("brandId", brandId);
            }
        } else if (!brandId.isBlank() && !matchesBrand(brandId, companyId, brandName)) {
            errors.add("MarcaId no existe, no corresponde a " + brandName
                    + " o pertenece a otra empresa.");
        }

        String categoryName = aggregate.path("category").asText().trim();
        String categoryId = aggregate.path("categoryId").asText().trim();
        if (categoryId.isBlank()) {
            categoryId = masters.findRootCategoryId(categoryName).orElse("");
            if (categoryId.isBlank()) {
                errors.add("No existe la categoría principal " + categoryName + ".");
            } else {
                aggregate.put("categoryId", categoryId);
            }
        } else if (!matchesCategory(categoryId, null, categoryName)) {
            errors.add("CategoriaId no existe o no corresponde a la categoría principal "
                    + categoryName + ".");
        }

        String subcategoryName = aggregate.path("subcategory").asText().trim();
        String subcategoryId = aggregate.path("subcategoryId").asText().trim();
        if (!subcategoryName.isBlank() && subcategoryId.isBlank() && !categoryId.isBlank()) {
            subcategoryId = masters.findChildCategoryId(categoryId, subcategoryName).orElse("");
            if (subcategoryId.isBlank()) {
                errors.add("No existe la subcategoría " + subcategoryName
                        + " dentro de " + categoryName + ".");
            } else {
                aggregate.put("subcategoryId", subcategoryId);
            }
        } else if (!subcategoryId.isBlank()
                && !matchesCategory(subcategoryId, categoryId, subcategoryName)) {
            errors.add("SubcategoriaId no existe o no pertenece a " + categoryName + ".");
        }

        String effectiveCategoryId = subcategoryId.isBlank() ? categoryId : subcategoryId;
        if (!brandId.isBlank() && !effectiveCategoryId.isBlank()
                && !hierarchy.brandAppliesToCategory(brandId, effectiveCategoryId)) {
            errors.add("La marca " + brandName + " no está relacionada con "
                    + (subcategoryName.isBlank()
                    ? categoryName
                    : categoryName + " > " + subcategoryName)
                    + " ni con una categoría antecesora.");
        }

        resolvePrices(aggregate, errors);
        if (!effectiveCategoryId.isBlank()) {
            resolveAttributes(candidate.familyCode(), effectiveCategoryId, aggregate, errors);
        }

        return new ProductImportCandidate(
                candidate.sourceRow(),
                candidate.familyCode(),
                candidate.productId(),
                candidate.expectedVersion(),
                aggregate,
                List.copyOf(warnings),
                List.copyOf(errors));
    }

    private void resolvePrices(ObjectNode aggregate, List<String> errors) {
        JsonNode rawPrices = aggregate.path("prices");
        if (rawPrices instanceof ArrayNode prices) {
            for (JsonNode raw : prices) {
                if (!(raw instanceof ObjectNode price)) continue;
                String currency = price.path("currency").asText("PEN")
                        .trim().toUpperCase(Locale.ROOT);
                if (!currency.equals("PEN")) {
                    errors.add("Precios: la moneda de todas las filas debe ser PEN.");
                }
                price.put("currency", "PEN");
                String listName = price.path("priceList").asText("General").trim();
                String listId = masters.findPriceListId(listName).orElse("");
                if (listId.isBlank()) {
                    errors.add("No existe la lista de precios " + listName
                            + ". Cárgala primero en Datos maestros.");
                } else {
                    price.put("priceListId", listId);
                }
            }
        }

        JsonNode rawPricing = aggregate.path("pricingConfiguration");
        if (!(rawPricing instanceof ObjectNode pricing)
                || !(pricing.path("lists") instanceof ArrayNode lists)) {
            return;
        }

        Map<String, String> resolvedListIds = new LinkedHashMap<>();
        for (JsonNode raw : lists) {
            if (!(raw instanceof ObjectNode list)) continue;
            String previousId = list.path("id").asText("").trim();
            String name = list.path("name").asText("General").trim();
            String id = masters.findPriceListId(name).orElse("");
            if (id.isBlank()) {
                errors.add("No existe la lista de precios " + name + ".");
            } else {
                list.put("id", id);
                if (!previousId.isBlank()) {
                    resolvedListIds.put(previousId, id);
                }
            }
            list.put("currency_code", "PEN");
            list.put("currency", "PEN");
        }

        JsonNode rawConfiguredPrices = pricing.path("prices");
        if (rawConfiguredPrices instanceof ArrayNode configuredPrices) {
            for (JsonNode raw : configuredPrices) {
                if (!(raw instanceof ObjectNode configuredPrice)) continue;
                String previousListId = configuredPrice.path("list_id").asText("").trim();
                String resolvedListId = resolvedListIds.get(previousListId);
                if (resolvedListId != null) {
                    configuredPrice.put("list_id", resolvedListId);
                }
            }
        }
    }

    private void resolveAttributes(
            String familyCode,
            String categoryId,
            ObjectNode aggregate,
            List<String> errors) {
        List<AttributeDefinition> definitions = effectiveAttributes(categoryId);
        Map<String, AttributeDefinition> byToken = new LinkedHashMap<>();
        for (AttributeDefinition definition : definitions) {
            register(byToken, definition.name(), definition);
            register(byToken, definition.key(), definition);
        }

        ObjectNode commonRaw = aggregate.path("attributes") instanceof ObjectNode object
                ? object.deepCopy()
                : objectMapper.createObjectNode();
        ArrayNode variants = aggregate.path("variants") instanceof ArrayNode array
                ? array
                : objectMapper.createArrayNode();

        ArrayNode values = objectMapper.createArrayNode();
        ArrayNode selectedOptions = objectMapper.createArrayNode();
        ObjectNode canonicalCommon = objectMapper.createObjectNode();
        Map<String, Set<String>> variantValuesByAttribute = new LinkedHashMap<>();
        Set<String> commonDefinitionIds = new LinkedHashSet<>();
        Map<String, Set<String>> variantDefinitionIds = new LinkedHashMap<>();

        resolveAttributeObject(
                familyCode,
                null,
                null,
                commonRaw,
                canonicalCommon,
                byToken,
                values,
                selectedOptions,
                commonDefinitionIds,
                variantValuesByAttribute,
                errors);

        for (JsonNode rawVariant : variants) {
            if (!(rawVariant instanceof ObjectNode variant)) continue;
            String variantId = variant.path("id").asText("").trim();
            String sku = variant.path("sku").asText("").trim().toUpperCase(Locale.ROOT);
            ObjectNode rawAttributes = variant.path("attributes") instanceof ObjectNode object
                    ? object.deepCopy()
                    : objectMapper.createObjectNode();
            ObjectNode canonicalVariant = objectMapper.createObjectNode();
            Set<String> resolvedForVariant = new LinkedHashSet<>();
            resolveAttributeObject(
                    familyCode,
                    variantId,
                    sku,
                    rawAttributes,
                    canonicalVariant,
                    byToken,
                    values,
                    selectedOptions,
                    resolvedForVariant,
                    variantValuesByAttribute,
                    errors);
            variant.set("attributes", canonicalVariant);
            variantDefinitionIds.put(variantId, resolvedForVariant);
        }

        aggregate.set("attributes", canonicalCommon);
        aggregate.set("attributeValues", values);
        aggregate.set("attributeOptions", selectedOptions);
        aggregate.set("familyAxes", inferAxes(
                aggregate.path("productType").asText("SINGLE"),
                definitions,
                variantValuesByAttribute,
                errors));

        validateCaptureLevels(
                definitions,
                commonDefinitionIds,
                variantDefinitionIds,
                variants,
                errors);
        validateRequiredAttributes(
                aggregate.path("status").asText("DRAFT"),
                definitions,
                commonDefinitionIds,
                variantDefinitionIds,
                variants,
                errors);
    }

    private void resolveAttributeObject(
            String familyCode,
            String variantId,
            String sku,
            ObjectNode rawAttributes,
            ObjectNode canonicalAttributes,
            Map<String, AttributeDefinition> byToken,
            ArrayNode values,
            ArrayNode selectedOptions,
            Set<String> resolvedDefinitionIds,
            Map<String, Set<String>> variantValuesByAttribute,
            List<String> errors) {
        Set<String> normalizedDefinitions = new LinkedHashSet<>();
        rawAttributes.propertyNames().forEach(rawName -> {
            JsonNode rawValue = rawAttributes.path(rawName);
            AttributeDefinition definition = byToken.get(normalize(rawName));
            String location = variantId == null
                    ? "Atributo de familia " + rawName
                    : "Atributo " + rawName + " de SKU " + sku;
            if (definition == null) {
                errors.add(location + ": no existe en la categoría seleccionada ni en sus antecesoras.");
                return;
            }
            if (!normalizedDefinitions.add(definition.id())) {
                errors.add(location + ": el atributo está repetido usando nombre y/o clave.");
                return;
            }

            String valueText = rawValue.path("value").asText("").trim();
            String unitText = rawValue.path("unit").asText("").trim();
            if (valueText.isBlank()) {
                errors.add(location + ": Valor es obligatorio.");
                return;
            }

            ObjectNode canonical = canonicalAttributes.putObject(definition.name());
            canonical.put("value", valueText);
            canonical.put("unit", unitText);

            String valueId = stableId(
                    "product-attribute",
                    familyCode,
                    definition.id(),
                    variantId == null ? "family" : variantId);
            ObjectNode value = values.addObject();
            value.put("id", valueId);
            value.put("categoria_atributo_id", definition.id());
            if (variantId == null) value.putNull("variante_id");
            else value.put("variante_id", variantId);

            String normalizedValue = resolveTypedValue(
                    definition,
                    valueText,
                    unitText,
                    value,
                    selectedOptions,
                    valueId,
                    location,
                    errors);
            if (normalizedValue != null) {
                resolvedDefinitionIds.add(definition.id());
                if (variantId != null) {
                    variantValuesByAttribute
                            .computeIfAbsent(definition.id(), ignored -> new LinkedHashSet<>())
                            .add(normalizedValue);
                }
            }
        });
    }

    private String resolveTypedValue(
            AttributeDefinition definition,
            String valueText,
            String unitText,
            ObjectNode value,
            ArrayNode selectedOptions,
            String valueId,
            String location,
            List<String> errors) {
        return switch (definition.type()) {
            case "texto_corto" -> resolveText(definition, valueText, value, location, errors);
            case "numero" -> resolveNumber(definition, valueText, value, location, errors);
            case "numero_unidad" -> resolveNumberWithUnit(
                    definition, valueText, unitText, value, location, errors);
            case "lista_unica" -> resolveOptions(
                    definition, valueText, value, selectedOptions, valueId, false, location, errors);
            case "lista_multiple" -> resolveOptions(
                    definition, valueText, value, selectedOptions, valueId, true, location, errors);
            case "si_no" -> resolveBoolean(valueText, value, location, errors);
            default -> {
                errors.add(location + ": TipoDato no soportado: " + definition.type() + ".");
                yield null;
            }
        };
    }

    private String resolveText(
            AttributeDefinition definition,
            String valueText,
            ObjectNode value,
            String location,
            List<String> errors) {
        if (definition.maximumLength() != null
                && valueText.length() > definition.maximumLength()) {
            errors.add(location + ": supera la longitud máxima de "
                    + definition.maximumLength() + " caracteres.");
            return null;
        }
        value.put("valor_texto", valueText);
        String normalized = normalize(valueText);
        value.put("valor_normalizado", normalized);
        return normalized;
    }

    private String resolveNumber(
            AttributeDefinition definition,
            String valueText,
            ObjectNode value,
            String location,
            List<String> errors) {
        BigDecimal number = parseNumber(valueText, location, errors);
        if (number == null || !validateNumber(definition, number, location, errors)) {
            return null;
        }
        value.put("valor_numero", number);
        String normalized = number.stripTrailingZeros().toPlainString();
        value.put("valor_normalizado", normalized);
        return normalized;
    }

    private String resolveNumberWithUnit(
            AttributeDefinition definition,
            String valueText,
            String unitText,
            ObjectNode value,
            String location,
            List<String> errors) {
        BigDecimal number = parseNumber(valueText, location, errors);
        if (number == null || !validateNumber(definition, number, location, errors)) {
            return null;
        }
        UnitDefinition unit = resolveUnit(definition.id(), unitText, location, errors);
        if (unit == null) return null;
        if (definition.magnitude() != null && !definition.magnitude().isBlank()
                && !normalize(definition.magnitude()).equals(normalize(unit.magnitude()))) {
            errors.add(location + ": la unidad " + unit.code()
                    + " no pertenece a la magnitud " + definition.magnitude() + ".");
            return null;
        }
        value.put("valor_numero", number);
        value.put("unidad_medida_id", unit.unitId());
        value.put("categoria_atributo_unidad_id", unit.relationId());
        String normalized = number.stripTrailingZeros().toPlainString()
                + " " + unit.code().toUpperCase(Locale.ROOT);
        value.put("valor_normalizado", normalized);
        return normalized;
    }

    private String resolveBoolean(
            String valueText,
            ObjectNode value,
            String location,
            List<String> errors) {
        String normalized = normalize(valueText);
        if (TRUE_VALUES.contains(normalized)) {
            value.put("valor_booleano", true);
            value.put("valor_normalizado", "true");
            return "true";
        }
        if (FALSE_VALUES.contains(normalized)) {
            value.put("valor_booleano", false);
            value.put("valor_normalizado", "false");
            return "false";
        }
        errors.add(location + ": usa SI/NO, TRUE/FALSE o 1/0.");
        return null;
    }

    private String resolveOptions(
            AttributeDefinition definition,
            String valueText,
            ObjectNode value,
            ArrayNode selectedOptions,
            String valueId,
            boolean multiple,
            String location,
            List<String> errors) {
        List<OptionDefinition> options = options(definition.id());
        Map<String, OptionDefinition> byToken = new LinkedHashMap<>();
        for (OptionDefinition option : options) {
            byToken.putIfAbsent(normalize(option.label()), option);
            byToken.putIfAbsent(normalize(option.code()), option);
        }

        String[] tokens = multiple ? valueText.split("[;|]") : new String[]{valueText};
        if (!multiple && (valueText.contains(";") || valueText.contains("|"))) {
            errors.add(location + ": lista_unica acepta una sola opción.");
            return null;
        }

        LinkedHashMap<String, OptionDefinition> selected = new LinkedHashMap<>();
        for (String token : tokens) {
            String cleaned = token.trim();
            if (cleaned.isBlank()) continue;
            OptionDefinition option = byToken.get(normalize(cleaned));
            if (option == null) {
                errors.add(location + ": la opción " + cleaned + " no existe.");
                continue;
            }
            selected.putIfAbsent(option.id(), option);
        }

        if (selected.isEmpty()) {
            errors.add(location + ": no contiene opciones válidas.");
            return null;
        }
        if (definition.maximumSelections() != null
                && selected.size() > definition.maximumSelections()) {
            errors.add(location + ": admite como máximo "
                    + definition.maximumSelections() + " selecciones.");
            return null;
        }

        String joinedLabels = String.join(
                "; ",
                selected.values().stream().map(OptionDefinition::label).toList());
        String joinedIds = String.join(";", selected.keySet());
        value.put("valor_texto", joinedLabels);
        value.put("valor_normalizado", joinedIds);
        for (OptionDefinition option : selected.values()) {
            ObjectNode selectedOption = selectedOptions.addObject();
            selectedOption.put("producto_atributo_id", valueId);
            selectedOption.put("categoria_atributo_id", definition.id());
            selectedOption.put("opcion_id", option.id());
        }
        return joinedIds;
    }

    private BigDecimal parseNumber(
            String valueText,
            String location,
            List<String> errors) {
        try {
            return new BigDecimal(valueText.replace(',', '.').trim());
        } catch (NumberFormatException exception) {
            errors.add(location + ": el valor debe ser numérico.");
            return null;
        }
    }

    private boolean validateNumber(
            AttributeDefinition definition,
            BigDecimal number,
            String location,
            List<String> errors) {
        boolean valid = true;
        if (definition.minimum() != null && number.compareTo(definition.minimum()) < 0) {
            errors.add(location + ": no puede ser menor que " + definition.minimum() + ".");
            valid = false;
        }
        if (definition.maximum() != null && number.compareTo(definition.maximum()) > 0) {
            errors.add(location + ": no puede ser mayor que " + definition.maximum() + ".");
            valid = false;
        }
        int scale = Math.max(number.stripTrailingZeros().scale(), 0);
        if (scale > definition.decimals()) {
            errors.add(location + ": admite como máximo "
                    + definition.decimals() + " decimales.");
            valid = false;
        }
        return valid;
    }

    private ArrayNode inferAxes(
            String productType,
            List<AttributeDefinition> definitions,
            Map<String, Set<String>> variantValuesByAttribute,
            List<String> errors) {
        ArrayNode axes = objectMapper.createArrayNode();
        if (!"MATRIX".equalsIgnoreCase(productType)) return axes;

        int order = 0;
        for (AttributeDefinition definition : definitions) {
            Set<String> values = variantValuesByAttribute.getOrDefault(definition.id(), Set.of());
            if (definition.canBeAxis() && values.size() > 1) {
                ObjectNode axis = axes.addObject();
                axis.put("categoria_atributo_id", definition.id());
                axis.put("orden", order++);
            }
        }
        if (order == 0) {
            errors.add("Un producto MATRIX debe tener al menos un atributo de variante "
                    + "marcado PuedeSerEje con valores distintos.");
        }
        return axes;
    }

    private void validateCaptureLevels(
            List<AttributeDefinition> definitions,
            Set<String> commonDefinitionIds,
            Map<String, Set<String>> variantDefinitionIds,
            ArrayNode variants,
            List<String> errors) {
        Set<String> anyVariantDefinitions = new LinkedHashSet<>();
        variantDefinitionIds.values().forEach(anyVariantDefinitions::addAll);
        for (AttributeDefinition definition : definitions) {
            boolean common = commonDefinitionIds.contains(definition.id());
            boolean variant = anyVariantDefinitions.contains(definition.id());
            switch (definition.captureLevel()) {
                case "familia" -> {
                    if (variant) {
                        errors.add("El atributo " + definition.name()
                                + " debe capturarse a nivel familia, no por SKU.");
                    }
                }
                case "variante" -> {
                    if (common) {
                        errors.add("El atributo " + definition.name()
                                + " debe capturarse por SKU, no a nivel familia.");
                    }
                }
                case "decidir" -> {
                    if (common && variant) {
                        errors.add("El atributo " + definition.name()
                                + " usa NivelCaptura=decidir: elige familia o variantes, no ambos.");
                    }
                }
                default -> errors.add("El atributo " + definition.name()
                        + " tiene NivelCaptura no soportado: "
                        + definition.captureLevel() + ".");
            }
        }
    }

    private void validateRequiredAttributes(
            String status,
            List<AttributeDefinition> definitions,
            Set<String> commonDefinitionIds,
            Map<String, Set<String>> variantDefinitionIds,
            ArrayNode variants,
            List<String> errors) {
        if (!"ACTIVE".equalsIgnoreCase(status)) return;

        List<ObjectNode> activeVariants = new ArrayList<>();
        for (JsonNode raw : variants) {
            if (!(raw instanceof ObjectNode variant)) continue;
            String variantStatus = variant.path("status").asText("ACTIVE");
            if (Set.of("", "ACTIVE", "1", "TRUE")
                    .contains(variantStatus.toUpperCase(Locale.ROOT))) {
                activeVariants.add(variant);
            }
        }

        for (AttributeDefinition definition : definitions) {
            if (!definition.requiredToActivate()) continue;
            boolean common = commonDefinitionIds.contains(definition.id());
            switch (definition.captureLevel()) {
                case "familia" -> {
                    if (!common) {
                        errors.add("Falta el atributo obligatorio de familia "
                                + definition.name() + ".");
                    }
                }
                case "variante" -> requireForEveryActiveVariant(
                        definition, activeVariants, variantDefinitionIds, errors);
                case "decidir" -> {
                    if (!common) {
                        requireForEveryActiveVariant(
                                definition, activeVariants, variantDefinitionIds, errors);
                    }
                }
                default -> {
                    // El error de enum ya se reporta en validateCaptureLevels.
                }
            }
        }
    }

    private void requireForEveryActiveVariant(
            AttributeDefinition definition,
            List<ObjectNode> activeVariants,
            Map<String, Set<String>> variantDefinitionIds,
            List<String> errors) {
        for (ObjectNode variant : activeVariants) {
            String variantId = variant.path("id").asText("");
            if (!variantDefinitionIds
                    .getOrDefault(variantId, Set.of())
                    .contains(definition.id())) {
                errors.add("Falta el atributo obligatorio " + definition.name()
                        + " en SKU " + variant.path("sku").asText("") + ".");
            }
        }
    }

    private List<AttributeDefinition> effectiveAttributes(String categoryId) {
        String sql = """
                WITH RECURSIVE category_tree(id, parent_id, depth) AS (
                    SELECT id, categoria_padre_id, 0
                    FROM categorias
                    WHERE id = ? AND deleted = FALSE
                    UNION ALL
                    SELECT parent.id, parent.categoria_padre_id, child.depth + 1
                    FROM categorias parent
                    JOIN category_tree child ON child.parent_id = parent.id
                    WHERE parent.deleted = FALSE
                )
                SELECT attribute.id, attribute.nombre, attribute.clave,
                       attribute.tipo_dato, attribute.nivel_captura,
                       attribute.requerido_activar, attribute.puede_ser_eje,
                       attribute.longitud_maxima, attribute.minimo, attribute.maximo,
                       attribute.decimales, attribute.magnitud,
                       attribute.maximo_selecciones, attribute.orden,
                       category_tree.depth
                FROM category_tree
                JOIN categoria_atributos attribute
                  ON attribute.categoria_id = category_tree.id
                WHERE attribute.estado = TRUE AND attribute.deleted = FALSE
                ORDER BY category_tree.depth, attribute.orden, attribute.nombre
                """;

        List<AttributeDefinition> rows = jdbc.query(
                sql,
                (rs, rowNumber) -> attributeDefinition(rs),
                categoryId);

        List<AttributeDefinition> effective = new ArrayList<>();
        Set<String> claimedAliases = new LinkedHashSet<>();
        for (AttributeDefinition definition : rows) {
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(normalize(definition.name()));
            aliases.add(normalize(definition.key()));
            aliases.remove("");
            boolean shadowed = aliases.stream().anyMatch(claimedAliases::contains);
            if (shadowed) continue;
            effective.add(definition);
            claimedAliases.addAll(aliases);
        }
        return List.copyOf(effective);
    }

    private AttributeDefinition attributeDefinition(ResultSet rs) throws SQLException {
        return new AttributeDefinition(
                rs.getString("id"),
                rs.getString("nombre"),
                rs.getString("clave"),
                rs.getString("tipo_dato").toLowerCase(Locale.ROOT),
                rs.getString("nivel_captura").toLowerCase(Locale.ROOT),
                rs.getBoolean("requerido_activar"),
                rs.getBoolean("puede_ser_eje"),
                nullableInteger(rs, "longitud_maxima"),
                rs.getBigDecimal("minimo"),
                rs.getBigDecimal("maximo"),
                rs.getInt("decimales"),
                rs.getString("magnitud"),
                nullableInteger(rs, "maximo_selecciones"),
                rs.getInt("orden"));
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private List<OptionDefinition> options(String attributeId) {
        return jdbc.query("""
                SELECT id, etiqueta, codigo
                FROM categoria_atributo_opciones
                WHERE categoria_atributo_id = ?
                  AND estado = TRUE AND deleted = FALSE
                ORDER BY orden, etiqueta
                """,
                (rs, rowNumber) -> new OptionDefinition(
                        rs.getString("id"),
                        rs.getString("etiqueta"),
                        rs.getString("codigo")),
                attributeId);
    }

    private UnitDefinition resolveUnit(
            String attributeId,
            String requested,
            String location,
            List<String> errors) {
        List<UnitDefinition> units = jdbc.query("""
                SELECT relation.id relation_id,
                       unit.id unit_id,
                       unit.codigo,
                       unit.nombre,
                       unit.simbolo,
                       unit.magnitud,
                       relation.es_predeterminada
                FROM categoria_atributo_unidades relation
                JOIN unidades_medida unit ON unit.id = relation.unidad_medida_id
                WHERE relation.categoria_atributo_id = ?
                  AND relation.estado = TRUE
                  AND relation.deleted = FALSE
                  AND unit.estado = TRUE
                  AND unit.deleted = FALSE
                ORDER BY relation.es_predeterminada DESC, relation.orden, unit.codigo
                """,
                (rs, rowNumber) -> new UnitDefinition(
                        rs.getString("relation_id"),
                        rs.getString("unit_id"),
                        rs.getString("codigo"),
                        rs.getString("nombre"),
                        rs.getString("simbolo"),
                        rs.getString("magnitud"),
                        rs.getBoolean("es_predeterminada")),
                attributeId);

        if (units.isEmpty()) {
            errors.add(location + ": el atributo no tiene unidades permitidas configuradas.");
            return null;
        }

        if (requested == null || requested.isBlank()) {
            UnitDefinition selected = units.stream()
                    .filter(UnitDefinition::defaultUnit)
                    .findFirst()
                    .orElse(units.size() == 1 ? units.get(0) : null);
            if (selected == null) {
                errors.add(location + ": indica una unidad; existen varias permitidas "
                        + "y ninguna está marcada como predeterminada.");
            }
            return selected;
        }

        String token = normalize(requested);
        return units.stream()
                .filter(unit -> token.equals(normalize(unit.code()))
                        || token.equals(normalize(unit.name()))
                        || token.equals(normalize(unit.symbol())))
                .findFirst()
                .orElseGet(() -> {
                    errors.add(location + ": la unidad " + requested
                            + " no está permitida para el atributo.");
                    return null;
                });
    }

    private boolean matchesCompany(String id, String name) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM empresas
                WHERE id = ? AND nombre_normalizado = ? AND deleted = FALSE
                """, Integer.class, id, masters.normalize(name));
        return count != null && count == 1;
    }

    private boolean matchesBrand(String id, String companyId, String name) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM marcas
                WHERE id = ? AND empresa_id = ?
                  AND nombre_normalizado = ? AND deleted = FALSE
                """, Integer.class, id, companyId, masters.normalize(name));
        return count != null && count == 1;
    }

    private boolean matchesCategory(String id, String parentId, String name) {
        Integer count;
        if (parentId == null || parentId.isBlank()) {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM categorias
                    WHERE id = ? AND categoria_padre_id IS NULL
                      AND nombre_normalizado = ? AND deleted = FALSE
                    """, Integer.class, id, masters.normalize(name));
        } else {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM categorias
                    WHERE id = ? AND categoria_padre_id = ?
                      AND nombre_normalizado = ? AND deleted = FALSE
                    """, Integer.class, id, parentId, masters.normalize(name));
        }
        return count != null && count == 1;
    }

    private void register(
            Map<String, AttributeDefinition> byToken,
            String value,
            AttributeDefinition definition) {
        String token = normalize(value);
        if (!token.isBlank()) byToken.putIfAbsent(token, definition);
    }

    private String normalize(String value) {
        return masters.normalize(value == null ? "" : value);
    }

    private String stableId(String namespace, String... values) {
        StringBuilder source = new StringBuilder(namespace);
        for (String value : values) {
            source.append(':')
                    .append(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
        }
        return UUID.nameUUIDFromBytes(
                source.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record AttributeDefinition(
            String id,
            String name,
            String key,
            String type,
            String captureLevel,
            boolean requiredToActivate,
            boolean canBeAxis,
            Integer maximumLength,
            BigDecimal minimum,
            BigDecimal maximum,
            int decimals,
            String magnitude,
            Integer maximumSelections,
            int order) {
    }

    private record OptionDefinition(String id, String label, String code) {
    }

    private record UnitDefinition(
            String relationId,
            String unitId,
            String code,
            String name,
            String symbol,
            String magnitude,
            boolean defaultUnit) {
    }
}
