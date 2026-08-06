from __future__ import annotations

from datetime import datetime
from pathlib import Path
import re
import shutil
import sys

ROOT = Path.cwd()
BASE = Path("src/main/java/com/abdul/catalogo")
RESOLVER_RELATIVE = BASE / "product/importing/service/ProductMasterDataResolver.java"
PARSER_RELATIVE = BASE / "product/importing/service/ProductWorkbookParser.java"
PROJECTION_RELATIVE = BASE / "product/service/ProductProjectionService.java"
VALIDATOR_RELATIVE = BASE / "product/importing/service/ProductImportValidator.java"
EXECUTOR_RELATIVE = BASE / "product/importing/service/ProductImportExecutor.java"

RESOLVER_SOURCE = 'package com.abdul.catalogo.product.importing.service;\n\nimport com.abdul.catalogo.masterdata.service.BrandCategoryHierarchyService;\nimport com.abdul.catalogo.masterdata.service.RelationalMasterDataService;\nimport org.springframework.jdbc.core.JdbcTemplate;\nimport org.springframework.stereotype.Component;\nimport tools.jackson.databind.JsonNode;\nimport tools.jackson.databind.ObjectMapper;\nimport tools.jackson.databind.node.ArrayNode;\nimport tools.jackson.databind.node.ObjectNode;\n\nimport java.math.BigDecimal;\nimport java.nio.charset.StandardCharsets;\nimport java.sql.ResultSet;\nimport java.sql.SQLException;\nimport java.util.ArrayList;\nimport java.util.LinkedHashMap;\nimport java.util.LinkedHashSet;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Map;\nimport java.util.Set;\nimport java.util.UUID;\n\n@Component\npublic class ProductMasterDataResolver {\n    private static final Set<String> TRUE_VALUES =\n            Set.of("si", "sí", "1", "true", "yes", "activo");\n    private static final Set<String> FALSE_VALUES =\n            Set.of("no", "0", "false", "inactivo");\n\n    private final RelationalMasterDataService masters;\n    private final BrandCategoryHierarchyService hierarchy;\n    private final JdbcTemplate jdbc;\n    private final ObjectMapper objectMapper;\n\n    public ProductMasterDataResolver(\n            RelationalMasterDataService masters,\n            BrandCategoryHierarchyService hierarchy,\n            JdbcTemplate jdbc,\n            ObjectMapper objectMapper) {\n        this.masters = masters;\n        this.hierarchy = hierarchy;\n        this.jdbc = jdbc;\n        this.objectMapper = objectMapper;\n    }\n\n    public ProductImportCandidate resolve(ProductImportCandidate candidate) {\n        ObjectNode aggregate = candidate.aggregate().deepCopy();\n        List<String> warnings = new ArrayList<>(candidate.warnings());\n        List<String> errors = new ArrayList<>(candidate.errors());\n\n        String companyName = aggregate.path("company").asText().trim();\n        String companyId = aggregate.path("companyId").asText().trim();\n        if (companyId.isBlank()) {\n            companyId = masters.findCompanyId(companyName).orElse("");\n            if (companyId.isBlank()) {\n                errors.add("No existe la empresa " + companyName + " en los datos maestros.");\n            } else {\n                aggregate.put("companyId", companyId);\n            }\n        } else if (!matchesCompany(companyId, companyName)) {\n            errors.add("EmpresaId no existe o no corresponde a " + companyName + ".");\n        }\n\n        String brandName = aggregate.path("brand").asText().trim();\n        String brandId = aggregate.path("brandId").asText().trim();\n        if (brandId.isBlank() && !companyId.isBlank()) {\n            brandId = masters.findBrandId(companyId, brandName).orElse("");\n            if (brandId.isBlank()) {\n                errors.add("No existe la marca " + brandName + " dentro de " + companyName + ".");\n            } else {\n                aggregate.put("brandId", brandId);\n            }\n        } else if (!brandId.isBlank() && !matchesBrand(brandId, companyId, brandName)) {\n            errors.add("MarcaId no existe, no corresponde a " + brandName\n                    + " o pertenece a otra empresa.");\n        }\n\n        String categoryName = aggregate.path("category").asText().trim();\n        String categoryId = aggregate.path("categoryId").asText().trim();\n        if (categoryId.isBlank()) {\n            categoryId = masters.findRootCategoryId(categoryName).orElse("");\n            if (categoryId.isBlank()) {\n                errors.add("No existe la categoría principal " + categoryName + ".");\n            } else {\n                aggregate.put("categoryId", categoryId);\n            }\n        } else if (!matchesCategory(categoryId, null, categoryName)) {\n            errors.add("CategoriaId no existe o no corresponde a la categoría principal "\n                    + categoryName + ".");\n        }\n\n        String subcategoryName = aggregate.path("subcategory").asText().trim();\n        String subcategoryId = aggregate.path("subcategoryId").asText().trim();\n        if (!subcategoryName.isBlank() && subcategoryId.isBlank() && !categoryId.isBlank()) {\n            subcategoryId = masters.findChildCategoryId(categoryId, subcategoryName).orElse("");\n            if (subcategoryId.isBlank()) {\n                errors.add("No existe la subcategoría " + subcategoryName\n                        + " dentro de " + categoryName + ".");\n            } else {\n                aggregate.put("subcategoryId", subcategoryId);\n            }\n        } else if (!subcategoryId.isBlank()\n                && !matchesCategory(subcategoryId, categoryId, subcategoryName)) {\n            errors.add("SubcategoriaId no existe o no pertenece a " + categoryName + ".");\n        }\n\n        String effectiveCategoryId = subcategoryId.isBlank() ? categoryId : subcategoryId;\n        if (!brandId.isBlank() && !effectiveCategoryId.isBlank()\n                && !hierarchy.brandAppliesToCategory(brandId, effectiveCategoryId)) {\n            errors.add("La marca " + brandName + " no está relacionada con "\n                    + (subcategoryName.isBlank()\n                    ? categoryName\n                    : categoryName + " > " + subcategoryName)\n                    + " ni con una categoría antecesora.");\n        }\n\n        resolvePrices(aggregate, errors);\n        if (!effectiveCategoryId.isBlank()) {\n            resolveAttributes(candidate.familyCode(), effectiveCategoryId, aggregate, errors);\n        }\n\n        return new ProductImportCandidate(\n                candidate.sourceRow(),\n                candidate.familyCode(),\n                candidate.productId(),\n                candidate.expectedVersion(),\n                aggregate,\n                List.copyOf(warnings),\n                List.copyOf(errors));\n    }\n\n    private void resolvePrices(ObjectNode aggregate, List<String> errors) {\n        JsonNode rawPrices = aggregate.path("prices");\n        if (rawPrices instanceof ArrayNode prices) {\n            for (JsonNode raw : prices) {\n                if (!(raw instanceof ObjectNode price)) continue;\n                String currency = price.path("currency").asText("PEN")\n                        .trim().toUpperCase(Locale.ROOT);\n                if (!currency.equals("PEN")) {\n                    errors.add("Precios: la moneda de todas las filas debe ser PEN.");\n                }\n                price.put("currency", "PEN");\n                String listName = price.path("priceList").asText("General").trim();\n                String listId = masters.findPriceListId(listName).orElse("");\n                if (listId.isBlank()) {\n                    errors.add("No existe la lista de precios " + listName\n                            + ". Cárgala primero en Datos maestros.");\n                } else {\n                    price.put("priceListId", listId);\n                }\n            }\n        }\n\n        JsonNode rawPricing = aggregate.path("pricingConfiguration");\n        if (!(rawPricing instanceof ObjectNode pricing)\n                || !(pricing.path("lists") instanceof ArrayNode lists)) {\n            return;\n        }\n\n        Map<String, String> resolvedListIds = new LinkedHashMap<>();\n        for (JsonNode raw : lists) {\n            if (!(raw instanceof ObjectNode list)) continue;\n            String previousId = list.path("id").asText("").trim();\n            String name = list.path("name").asText("General").trim();\n            String id = masters.findPriceListId(name).orElse("");\n            if (id.isBlank()) {\n                errors.add("No existe la lista de precios " + name + ".");\n            } else {\n                list.put("id", id);\n                if (!previousId.isBlank()) {\n                    resolvedListIds.put(previousId, id);\n                }\n            }\n            list.put("currency_code", "PEN");\n            list.put("currency", "PEN");\n        }\n\n        JsonNode rawConfiguredPrices = pricing.path("prices");\n        if (rawConfiguredPrices instanceof ArrayNode configuredPrices) {\n            for (JsonNode raw : configuredPrices) {\n                if (!(raw instanceof ObjectNode configuredPrice)) continue;\n                String previousListId = configuredPrice.path("list_id").asText("").trim();\n                String resolvedListId = resolvedListIds.get(previousListId);\n                if (resolvedListId != null) {\n                    configuredPrice.put("list_id", resolvedListId);\n                }\n            }\n        }\n    }\n\n    private void resolveAttributes(\n            String familyCode,\n            String categoryId,\n            ObjectNode aggregate,\n            List<String> errors) {\n        List<AttributeDefinition> definitions = effectiveAttributes(categoryId);\n        Map<String, AttributeDefinition> byToken = new LinkedHashMap<>();\n        for (AttributeDefinition definition : definitions) {\n            register(byToken, definition.name(), definition);\n            register(byToken, definition.key(), definition);\n        }\n\n        ObjectNode commonRaw = aggregate.path("attributes") instanceof ObjectNode object\n                ? object.deepCopy()\n                : objectMapper.createObjectNode();\n        ArrayNode variants = aggregate.path("variants") instanceof ArrayNode array\n                ? array\n                : objectMapper.createArrayNode();\n\n        ArrayNode values = objectMapper.createArrayNode();\n        ArrayNode selectedOptions = objectMapper.createArrayNode();\n        ObjectNode canonicalCommon = objectMapper.createObjectNode();\n        Map<String, Set<String>> variantValuesByAttribute = new LinkedHashMap<>();\n        Set<String> commonDefinitionIds = new LinkedHashSet<>();\n        Map<String, Set<String>> variantDefinitionIds = new LinkedHashMap<>();\n\n        resolveAttributeObject(\n                familyCode,\n                null,\n                null,\n                commonRaw,\n                canonicalCommon,\n                byToken,\n                values,\n                selectedOptions,\n                commonDefinitionIds,\n                variantValuesByAttribute,\n                errors);\n\n        for (JsonNode rawVariant : variants) {\n            if (!(rawVariant instanceof ObjectNode variant)) continue;\n            String variantId = variant.path("id").asText("").trim();\n            String sku = variant.path("sku").asText("").trim().toUpperCase(Locale.ROOT);\n            ObjectNode rawAttributes = variant.path("attributes") instanceof ObjectNode object\n                    ? object.deepCopy()\n                    : objectMapper.createObjectNode();\n            ObjectNode canonicalVariant = objectMapper.createObjectNode();\n            Set<String> resolvedForVariant = new LinkedHashSet<>();\n            resolveAttributeObject(\n                    familyCode,\n                    variantId,\n                    sku,\n                    rawAttributes,\n                    canonicalVariant,\n                    byToken,\n                    values,\n                    selectedOptions,\n                    resolvedForVariant,\n                    variantValuesByAttribute,\n                    errors);\n            variant.set("attributes", canonicalVariant);\n            variantDefinitionIds.put(variantId, resolvedForVariant);\n        }\n\n        aggregate.set("attributes", canonicalCommon);\n        aggregate.set("attributeValues", values);\n        aggregate.set("attributeOptions", selectedOptions);\n        aggregate.set("familyAxes", inferAxes(\n                aggregate.path("productType").asText("SINGLE"),\n                definitions,\n                variantValuesByAttribute,\n                errors));\n\n        validateCaptureLevels(\n                definitions,\n                commonDefinitionIds,\n                variantDefinitionIds,\n                variants,\n                errors);\n        validateRequiredAttributes(\n                aggregate.path("status").asText("DRAFT"),\n                definitions,\n                commonDefinitionIds,\n                variantDefinitionIds,\n                variants,\n                errors);\n    }\n\n    private void resolveAttributeObject(\n            String familyCode,\n            String variantId,\n            String sku,\n            ObjectNode rawAttributes,\n            ObjectNode canonicalAttributes,\n            Map<String, AttributeDefinition> byToken,\n            ArrayNode values,\n            ArrayNode selectedOptions,\n            Set<String> resolvedDefinitionIds,\n            Map<String, Set<String>> variantValuesByAttribute,\n            List<String> errors) {\n        Set<String> normalizedDefinitions = new LinkedHashSet<>();\n        rawAttributes.propertyNames().forEachRemaining(rawName -> {\n            JsonNode rawValue = rawAttributes.path(rawName);\n            AttributeDefinition definition = byToken.get(normalize(rawName));\n            String location = variantId == null\n                    ? "Atributo de familia " + rawName\n                    : "Atributo " + rawName + " de SKU " + sku;\n            if (definition == null) {\n                errors.add(location + ": no existe en la categoría seleccionada ni en sus antecesoras.");\n                return;\n            }\n            if (!normalizedDefinitions.add(definition.id())) {\n                errors.add(location + ": el atributo está repetido usando nombre y/o clave.");\n                return;\n            }\n\n            String valueText = rawValue.path("value").asText("").trim();\n            String unitText = rawValue.path("unit").asText("").trim();\n            if (valueText.isBlank()) {\n                errors.add(location + ": Valor es obligatorio.");\n                return;\n            }\n\n            ObjectNode canonical = canonicalAttributes.putObject(definition.name());\n            canonical.put("value", valueText);\n            canonical.put("unit", unitText);\n\n            String valueId = stableId(\n                    "product-attribute",\n                    familyCode,\n                    definition.id(),\n                    variantId == null ? "family" : variantId);\n            ObjectNode value = values.addObject();\n            value.put("id", valueId);\n            value.put("categoria_atributo_id", definition.id());\n            if (variantId == null) value.putNull("variante_id");\n            else value.put("variante_id", variantId);\n\n            String normalizedValue = resolveTypedValue(\n                    definition,\n                    valueText,\n                    unitText,\n                    value,\n                    selectedOptions,\n                    valueId,\n                    location,\n                    errors);\n            if (normalizedValue != null) {\n                resolvedDefinitionIds.add(definition.id());\n                if (variantId != null) {\n                    variantValuesByAttribute\n                            .computeIfAbsent(definition.id(), ignored -> new LinkedHashSet<>())\n                            .add(normalizedValue);\n                }\n            }\n        });\n    }\n\n    private String resolveTypedValue(\n            AttributeDefinition definition,\n            String valueText,\n            String unitText,\n            ObjectNode value,\n            ArrayNode selectedOptions,\n            String valueId,\n            String location,\n            List<String> errors) {\n        return switch (definition.type()) {\n            case "texto_corto" -> resolveText(definition, valueText, value, location, errors);\n            case "numero" -> resolveNumber(definition, valueText, value, location, errors);\n            case "numero_unidad" -> resolveNumberWithUnit(\n                    definition, valueText, unitText, value, location, errors);\n            case "lista_unica" -> resolveOptions(\n                    definition, valueText, value, selectedOptions, valueId, false, location, errors);\n            case "lista_multiple" -> resolveOptions(\n                    definition, valueText, value, selectedOptions, valueId, true, location, errors);\n            case "si_no" -> resolveBoolean(valueText, value, location, errors);\n            default -> {\n                errors.add(location + ": TipoDato no soportado: " + definition.type() + ".");\n                yield null;\n            }\n        };\n    }\n\n    private String resolveText(\n            AttributeDefinition definition,\n            String valueText,\n            ObjectNode value,\n            String location,\n            List<String> errors) {\n        if (definition.maximumLength() != null\n                && valueText.length() > definition.maximumLength()) {\n            errors.add(location + ": supera la longitud máxima de "\n                    + definition.maximumLength() + " caracteres.");\n            return null;\n        }\n        value.put("valor_texto", valueText);\n        String normalized = normalize(valueText);\n        value.put("valor_normalizado", normalized);\n        return normalized;\n    }\n\n    private String resolveNumber(\n            AttributeDefinition definition,\n            String valueText,\n            ObjectNode value,\n            String location,\n            List<String> errors) {\n        BigDecimal number = parseNumber(valueText, location, errors);\n        if (number == null || !validateNumber(definition, number, location, errors)) {\n            return null;\n        }\n        value.put("valor_numero", number);\n        String normalized = number.stripTrailingZeros().toPlainString();\n        value.put("valor_normalizado", normalized);\n        return normalized;\n    }\n\n    private String resolveNumberWithUnit(\n            AttributeDefinition definition,\n            String valueText,\n            String unitText,\n            ObjectNode value,\n            String location,\n            List<String> errors) {\n        BigDecimal number = parseNumber(valueText, location, errors);\n        if (number == null || !validateNumber(definition, number, location, errors)) {\n            return null;\n        }\n        UnitDefinition unit = resolveUnit(definition.id(), unitText, location, errors);\n        if (unit == null) return null;\n        if (definition.magnitude() != null && !definition.magnitude().isBlank()\n                && !normalize(definition.magnitude()).equals(normalize(unit.magnitude()))) {\n            errors.add(location + ": la unidad " + unit.code()\n                    + " no pertenece a la magnitud " + definition.magnitude() + ".");\n            return null;\n        }\n        value.put("valor_numero", number);\n        value.put("unidad_medida_id", unit.unitId());\n        value.put("categoria_atributo_unidad_id", unit.relationId());\n        String normalized = number.stripTrailingZeros().toPlainString()\n                + " " + unit.code().toUpperCase(Locale.ROOT);\n        value.put("valor_normalizado", normalized);\n        return normalized;\n    }\n\n    private String resolveBoolean(\n            String valueText,\n            ObjectNode value,\n            String location,\n            List<String> errors) {\n        String normalized = normalize(valueText);\n        if (TRUE_VALUES.contains(normalized)) {\n            value.put("valor_booleano", true);\n            value.put("valor_normalizado", "true");\n            return "true";\n        }\n        if (FALSE_VALUES.contains(normalized)) {\n            value.put("valor_booleano", false);\n            value.put("valor_normalizado", "false");\n            return "false";\n        }\n        errors.add(location + ": usa SI/NO, TRUE/FALSE o 1/0.");\n        return null;\n    }\n\n    private String resolveOptions(\n            AttributeDefinition definition,\n            String valueText,\n            ObjectNode value,\n            ArrayNode selectedOptions,\n            String valueId,\n            boolean multiple,\n            String location,\n            List<String> errors) {\n        List<OptionDefinition> options = options(definition.id());\n        Map<String, OptionDefinition> byToken = new LinkedHashMap<>();\n        for (OptionDefinition option : options) {\n            byToken.putIfAbsent(normalize(option.label()), option);\n            byToken.putIfAbsent(normalize(option.code()), option);\n        }\n\n        String[] tokens = multiple ? valueText.split("[;|]") : new String[]{valueText};\n        if (!multiple && (valueText.contains(";") || valueText.contains("|"))) {\n            errors.add(location + ": lista_unica acepta una sola opción.");\n            return null;\n        }\n\n        LinkedHashMap<String, OptionDefinition> selected = new LinkedHashMap<>();\n        for (String token : tokens) {\n            String cleaned = token.trim();\n            if (cleaned.isBlank()) continue;\n            OptionDefinition option = byToken.get(normalize(cleaned));\n            if (option == null) {\n                errors.add(location + ": la opción " + cleaned + " no existe.");\n                continue;\n            }\n            selected.putIfAbsent(option.id(), option);\n        }\n\n        if (selected.isEmpty()) {\n            errors.add(location + ": no contiene opciones válidas.");\n            return null;\n        }\n        if (definition.maximumSelections() != null\n                && selected.size() > definition.maximumSelections()) {\n            errors.add(location + ": admite como máximo "\n                    + definition.maximumSelections() + " selecciones.");\n            return null;\n        }\n\n        String joinedLabels = String.join(\n                "; ",\n                selected.values().stream().map(OptionDefinition::label).toList());\n        String joinedIds = String.join(";", selected.keySet());\n        value.put("valor_texto", joinedLabels);\n        value.put("valor_normalizado", joinedIds);\n        for (OptionDefinition option : selected.values()) {\n            ObjectNode selectedOption = selectedOptions.addObject();\n            selectedOption.put("producto_atributo_id", valueId);\n            selectedOption.put("opcion_id", option.id());\n        }\n        return joinedIds;\n    }\n\n    private BigDecimal parseNumber(\n            String valueText,\n            String location,\n            List<String> errors) {\n        try {\n            return new BigDecimal(valueText.replace(\',\', \'.\').trim());\n        } catch (NumberFormatException exception) {\n            errors.add(location + ": el valor debe ser numérico.");\n            return null;\n        }\n    }\n\n    private boolean validateNumber(\n            AttributeDefinition definition,\n            BigDecimal number,\n            String location,\n            List<String> errors) {\n        boolean valid = true;\n        if (definition.minimum() != null && number.compareTo(definition.minimum()) < 0) {\n            errors.add(location + ": no puede ser menor que " + definition.minimum() + ".");\n            valid = false;\n        }\n        if (definition.maximum() != null && number.compareTo(definition.maximum()) > 0) {\n            errors.add(location + ": no puede ser mayor que " + definition.maximum() + ".");\n            valid = false;\n        }\n        int scale = Math.max(number.stripTrailingZeros().scale(), 0);\n        if (scale > definition.decimals()) {\n            errors.add(location + ": admite como máximo "\n                    + definition.decimals() + " decimales.");\n            valid = false;\n        }\n        return valid;\n    }\n\n    private ArrayNode inferAxes(\n            String productType,\n            List<AttributeDefinition> definitions,\n            Map<String, Set<String>> variantValuesByAttribute,\n            List<String> errors) {\n        ArrayNode axes = objectMapper.createArrayNode();\n        if (!"MATRIX".equalsIgnoreCase(productType)) return axes;\n\n        int order = 0;\n        for (AttributeDefinition definition : definitions) {\n            Set<String> values = variantValuesByAttribute.getOrDefault(definition.id(), Set.of());\n            if (definition.canBeAxis() && values.size() > 1) {\n                ObjectNode axis = axes.addObject();\n                axis.put("categoria_atributo_id", definition.id());\n                axis.put("orden", order++);\n            }\n        }\n        if (order == 0) {\n            errors.add("Un producto MATRIX debe tener al menos un atributo de variante "\n                    + "marcado PuedeSerEje con valores distintos.");\n        }\n        return axes;\n    }\n\n    private void validateCaptureLevels(\n            List<AttributeDefinition> definitions,\n            Set<String> commonDefinitionIds,\n            Map<String, Set<String>> variantDefinitionIds,\n            ArrayNode variants,\n            List<String> errors) {\n        Set<String> anyVariantDefinitions = new LinkedHashSet<>();\n        variantDefinitionIds.values().forEach(anyVariantDefinitions::addAll);\n        for (AttributeDefinition definition : definitions) {\n            boolean common = commonDefinitionIds.contains(definition.id());\n            boolean variant = anyVariantDefinitions.contains(definition.id());\n            switch (definition.captureLevel()) {\n                case "familia" -> {\n                    if (variant) {\n                        errors.add("El atributo " + definition.name()\n                                + " debe capturarse a nivel familia, no por SKU.");\n                    }\n                }\n                case "variante" -> {\n                    if (common) {\n                        errors.add("El atributo " + definition.name()\n                                + " debe capturarse por SKU, no a nivel familia.");\n                    }\n                }\n                case "decidir" -> {\n                    if (common && variant) {\n                        errors.add("El atributo " + definition.name()\n                                + " usa NivelCaptura=decidir: elige familia o variantes, no ambos.");\n                    }\n                }\n                default -> errors.add("El atributo " + definition.name()\n                        + " tiene NivelCaptura no soportado: "\n                        + definition.captureLevel() + ".");\n            }\n        }\n    }\n\n    private void validateRequiredAttributes(\n            String status,\n            List<AttributeDefinition> definitions,\n            Set<String> commonDefinitionIds,\n            Map<String, Set<String>> variantDefinitionIds,\n            ArrayNode variants,\n            List<String> errors) {\n        if (!"ACTIVE".equalsIgnoreCase(status)) return;\n\n        List<ObjectNode> activeVariants = new ArrayList<>();\n        for (JsonNode raw : variants) {\n            if (!(raw instanceof ObjectNode variant)) continue;\n            String variantStatus = variant.path("status").asText("ACTIVE");\n            if (Set.of("", "ACTIVE", "1", "TRUE")\n                    .contains(variantStatus.toUpperCase(Locale.ROOT))) {\n                activeVariants.add(variant);\n            }\n        }\n\n        for (AttributeDefinition definition : definitions) {\n            if (!definition.requiredToActivate()) continue;\n            boolean common = commonDefinitionIds.contains(definition.id());\n            switch (definition.captureLevel()) {\n                case "familia" -> {\n                    if (!common) {\n                        errors.add("Falta el atributo obligatorio de familia "\n                                + definition.name() + ".");\n                    }\n                }\n                case "variante" -> requireForEveryActiveVariant(\n                        definition, activeVariants, variantDefinitionIds, errors);\n                case "decidir" -> {\n                    if (!common) {\n                        requireForEveryActiveVariant(\n                                definition, activeVariants, variantDefinitionIds, errors);\n                    }\n                }\n                default -> {\n                    // El error de enum ya se reporta en validateCaptureLevels.\n                }\n            }\n        }\n    }\n\n    private void requireForEveryActiveVariant(\n            AttributeDefinition definition,\n            List<ObjectNode> activeVariants,\n            Map<String, Set<String>> variantDefinitionIds,\n            List<String> errors) {\n        for (ObjectNode variant : activeVariants) {\n            String variantId = variant.path("id").asText("");\n            if (!variantDefinitionIds\n                    .getOrDefault(variantId, Set.of())\n                    .contains(definition.id())) {\n                errors.add("Falta el atributo obligatorio " + definition.name()\n                        + " en SKU " + variant.path("sku").asText("") + ".");\n            }\n        }\n    }\n\n    private List<AttributeDefinition> effectiveAttributes(String categoryId) {\n        String sql = """\n                WITH RECURSIVE category_tree(id, parent_id, depth) AS (\n                    SELECT id, categoria_padre_id, 0\n                    FROM categorias\n                    WHERE id = ? AND deleted = FALSE\n                    UNION ALL\n                    SELECT parent.id, parent.categoria_padre_id, child.depth + 1\n                    FROM categorias parent\n                    JOIN category_tree child ON child.parent_id = parent.id\n                    WHERE parent.deleted = FALSE\n                )\n                SELECT attribute.id, attribute.nombre, attribute.clave,\n                       attribute.tipo_dato, attribute.nivel_captura,\n                       attribute.requerido_activar, attribute.puede_ser_eje,\n                       attribute.longitud_maxima, attribute.minimo, attribute.maximo,\n                       attribute.decimales, attribute.magnitud,\n                       attribute.maximo_selecciones, attribute.orden,\n                       category_tree.depth\n                FROM category_tree\n                JOIN categoria_atributos attribute\n                  ON attribute.categoria_id = category_tree.id\n                WHERE attribute.estado = TRUE AND attribute.deleted = FALSE\n                ORDER BY category_tree.depth, attribute.orden, attribute.nombre\n                """;\n\n        List<AttributeDefinition> rows = jdbc.query(\n                sql,\n                (rs, rowNumber) -> attributeDefinition(rs),\n                categoryId);\n\n        List<AttributeDefinition> effective = new ArrayList<>();\n        Set<String> claimedAliases = new LinkedHashSet<>();\n        for (AttributeDefinition definition : rows) {\n            Set<String> aliases = new LinkedHashSet<>();\n            aliases.add(normalize(definition.name()));\n            aliases.add(normalize(definition.key()));\n            aliases.remove("");\n            boolean shadowed = aliases.stream().anyMatch(claimedAliases::contains);\n            if (shadowed) continue;\n            effective.add(definition);\n            claimedAliases.addAll(aliases);\n        }\n        return List.copyOf(effective);\n    }\n\n    private AttributeDefinition attributeDefinition(ResultSet rs) throws SQLException {\n        return new AttributeDefinition(\n                rs.getString("id"),\n                rs.getString("nombre"),\n                rs.getString("clave"),\n                rs.getString("tipo_dato").toLowerCase(Locale.ROOT),\n                rs.getString("nivel_captura").toLowerCase(Locale.ROOT),\n                rs.getBoolean("requerido_activar"),\n                rs.getBoolean("puede_ser_eje"),\n                nullableInteger(rs, "longitud_maxima"),\n                rs.getBigDecimal("minimo"),\n                rs.getBigDecimal("maximo"),\n                rs.getInt("decimales"),\n                rs.getString("magnitud"),\n                nullableInteger(rs, "maximo_selecciones"),\n                rs.getInt("orden"));\n    }\n\n    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {\n        int value = rs.getInt(column);\n        return rs.wasNull() ? null : value;\n    }\n\n    private List<OptionDefinition> options(String attributeId) {\n        return jdbc.query("""\n                SELECT id, etiqueta, codigo\n                FROM categoria_atributo_opciones\n                WHERE categoria_atributo_id = ?\n                  AND estado = TRUE AND deleted = FALSE\n                ORDER BY orden, etiqueta\n                """,\n                (rs, rowNumber) -> new OptionDefinition(\n                        rs.getString("id"),\n                        rs.getString("etiqueta"),\n                        rs.getString("codigo")),\n                attributeId);\n    }\n\n    private UnitDefinition resolveUnit(\n            String attributeId,\n            String requested,\n            String location,\n            List<String> errors) {\n        List<UnitDefinition> units = jdbc.query("""\n                SELECT relation.id relation_id,\n                       unit.id unit_id,\n                       unit.codigo,\n                       unit.nombre,\n                       unit.simbolo,\n                       unit.magnitud,\n                       relation.es_predeterminada\n                FROM categoria_atributo_unidades relation\n                JOIN unidades_medida unit ON unit.id = relation.unidad_medida_id\n                WHERE relation.categoria_atributo_id = ?\n                  AND relation.estado = TRUE\n                  AND relation.deleted = FALSE\n                  AND unit.estado = TRUE\n                  AND unit.deleted = FALSE\n                ORDER BY relation.es_predeterminada DESC, relation.orden, unit.codigo\n                """,\n                (rs, rowNumber) -> new UnitDefinition(\n                        rs.getString("relation_id"),\n                        rs.getString("unit_id"),\n                        rs.getString("codigo"),\n                        rs.getString("nombre"),\n                        rs.getString("simbolo"),\n                        rs.getString("magnitud"),\n                        rs.getBoolean("es_predeterminada")),\n                attributeId);\n\n        if (units.isEmpty()) {\n            errors.add(location + ": el atributo no tiene unidades permitidas configuradas.");\n            return null;\n        }\n\n        if (requested == null || requested.isBlank()) {\n            UnitDefinition selected = units.stream()\n                    .filter(UnitDefinition::defaultUnit)\n                    .findFirst()\n                    .orElse(units.size() == 1 ? units.get(0) : null);\n            if (selected == null) {\n                errors.add(location + ": indica una unidad; existen varias permitidas "\n                        + "y ninguna está marcada como predeterminada.");\n            }\n            return selected;\n        }\n\n        String token = normalize(requested);\n        return units.stream()\n                .filter(unit -> token.equals(normalize(unit.code()))\n                        || token.equals(normalize(unit.name()))\n                        || token.equals(normalize(unit.symbol())))\n                .findFirst()\n                .orElseGet(() -> {\n                    errors.add(location + ": la unidad " + requested\n                            + " no está permitida para el atributo.");\n                    return null;\n                });\n    }\n\n    private boolean matchesCompany(String id, String name) {\n        Integer count = jdbc.queryForObject("""\n                SELECT COUNT(*) FROM empresas\n                WHERE id = ? AND nombre_normalizado = ? AND deleted = FALSE\n                """, Integer.class, id, masters.normalize(name));\n        return count != null && count == 1;\n    }\n\n    private boolean matchesBrand(String id, String companyId, String name) {\n        Integer count = jdbc.queryForObject("""\n                SELECT COUNT(*) FROM marcas\n                WHERE id = ? AND empresa_id = ?\n                  AND nombre_normalizado = ? AND deleted = FALSE\n                """, Integer.class, id, companyId, masters.normalize(name));\n        return count != null && count == 1;\n    }\n\n    private boolean matchesCategory(String id, String parentId, String name) {\n        Integer count;\n        if (parentId == null || parentId.isBlank()) {\n            count = jdbc.queryForObject("""\n                    SELECT COUNT(*) FROM categorias\n                    WHERE id = ? AND categoria_padre_id IS NULL\n                      AND nombre_normalizado = ? AND deleted = FALSE\n                    """, Integer.class, id, masters.normalize(name));\n        } else {\n            count = jdbc.queryForObject("""\n                    SELECT COUNT(*) FROM categorias\n                    WHERE id = ? AND categoria_padre_id = ?\n                      AND nombre_normalizado = ? AND deleted = FALSE\n                    """, Integer.class, id, parentId, masters.normalize(name));\n        }\n        return count != null && count == 1;\n    }\n\n    private void register(\n            Map<String, AttributeDefinition> byToken,\n            String value,\n            AttributeDefinition definition) {\n        String token = normalize(value);\n        if (!token.isBlank()) byToken.putIfAbsent(token, definition);\n    }\n\n    private String normalize(String value) {\n        return masters.normalize(value == null ? "" : value);\n    }\n\n    private String stableId(String namespace, String... values) {\n        StringBuilder source = new StringBuilder(namespace);\n        for (String value : values) {\n            source.append(\':\')\n                    .append(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));\n        }\n        return UUID.nameUUIDFromBytes(\n                source.toString().getBytes(StandardCharsets.UTF_8)).toString();\n    }\n\n    private record AttributeDefinition(\n            String id,\n            String name,\n            String key,\n            String type,\n            String captureLevel,\n            boolean requiredToActivate,\n            boolean canBeAxis,\n            Integer maximumLength,\n            BigDecimal minimum,\n            BigDecimal maximum,\n            int decimals,\n            String magnitude,\n            Integer maximumSelections,\n            int order) {\n    }\n\n    private record OptionDefinition(String id, String label, String code) {\n    }\n\n    private record UnitDefinition(\n            String relationId,\n            String unitId,\n            String code,\n            String name,\n            String symbol,\n            String magnitude,\n            boolean defaultUnit) {\n    }\n}\n'


def backend_root() -> Path:
    for candidate in (ROOT / "backend-app-catalogo", ROOT):
        if (candidate / RESOLVER_RELATIVE).exists() and (candidate / "pom.xml").exists():
            return candidate
    raise SystemExit(
        "No se encontró el backend. Ejecuta este script desde la raíz del "
        "repositorio combinado o desde backend-app-catalogo."
    )


BACKEND = backend_root()
RESOLVER = BACKEND / RESOLVER_RELATIVE
PARSER = BACKEND / PARSER_RELATIVE
PROJECTION = BACKEND / PROJECTION_RELATIVE
VALIDATOR = BACKEND / VALIDATOR_RELATIVE
EXECUTOR = BACKEND / EXECUTOR_RELATIVE
BACKUP_ROOT = (
    BACKEND / ".correction_backups" / datetime.now().strftime("%Y%m%d_%H%M%S")
)


def read(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"No se encontró el archivo: {path}")
    return path.read_text(encoding="utf-8")


def backup(path: Path) -> None:
    destination = BACKUP_ROOT / path.relative_to(BACKEND)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"Modificado: {path.relative_to(BACKEND)}")


def replace_once(content: str, old: str, new: str, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"No se pudo aplicar '{label}'. "
            f"Se esperaba 1 coincidencia y se encontraron {count}. "
            "El archivo puede haber cambiado."
        )
    return content.replace(old, new, 1)


def replace_unless_applied(
    content: str,
    old: str,
    new: str,
    marker: str,
    label: str,
) -> str:
    if marker in content:
        print(f"Ya aplicada: {label}")
        return content
    return replace_once(content, old, new, label)


def replace_resolver() -> None:
    content = read(RESOLVER)
    if "private void resolveAttributes(" in content and "effectiveAttributes(" in content:
        print("Ya aplicada: resolución completa de atributos")
        return
    backup(RESOLVER)
    write(RESOLVER, RESOLVER_SOURCE)


def patch_parser() -> None:
    content = read(PARSER)

    content = replace_unless_applied(
        content,
        """        for (RowData product : products) {
            String family = family(product);
            if (!family.isBlank()) productByFamily.putIfAbsent(family, product);
        }
""",
        """        for (RowData product : products) {
            String family = family(product);
            if (family.isBlank()) continue;
            RowData previous = productByFamily.putIfAbsent(family, product);
            if (previous != null) {
                throw new BusinessRuleException(
                        "DUPLICATE_PRODUCT_FAMILY",
                        "El código de familia " + family + " está repetido en Productos, filas "
                                + previous.rowNumber() + " y " + product.rowNumber() + ".");
            }
        }
""",
        '"DUPLICATE_PRODUCT_FAMILY"',
        "rechazar CodigoFamilia repetido",
    )

    content = replace_unless_applied(
        content,
        """    private Long integer(String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value.trim().replace(".0", ""));
        } catch (NumberFormatException exception) {
            errors.add(label + " debe ser un entero.");
            return null;
        }
    }
""",
        """    private Long integer(String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim().replace(',', '.')).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            errors.add(label + " debe ser un entero.");
            return null;
        }
    }
""",
        "longValueExact()",
        "validar Version como entero exacto",
    )

    backup(PARSER)
    write(PARSER, content)


def patch_projection() -> None:
    content = read(PROJECTION)

    content = replace_unless_applied(
        content,
        '        validateImages(aggregate.path("images"));\n',
        '        validateImages(aggregate.path("images"), variantSummary.allSkus());\n',
        'validateImages(aggregate.path("images"), variantSummary.allSkus())',
        "validar SKU de imágenes",
    )

    content = replace_unless_applied(
        content,
        """            String variantStatus = firstText(variant, "status", "estado").toUpperCase(Locale.ROOT);
            if (variantStatus.isBlank() || variantStatus.equals("ACTIVE") || variantStatus.equals("1") || variantStatus.equals("TRUE")) {
                active.add(sku);
            }
""",
        """            String variantStatus = firstText(variant, "status", "estado").toUpperCase(Locale.ROOT);
            if (!Set.of("", "ACTIVE", "INACTIVE", "1", "0", "TRUE", "FALSE").contains(variantStatus)) {
                throw new BusinessRuleException(
                        "INVALID_VARIANT_STATUS",
                        "El estado de la variante " + sku + " no es válido: " + variantStatus + ".");
            }
            if (variantStatus.isBlank() || variantStatus.equals("ACTIVE")
                    || variantStatus.equals("1") || variantStatus.equals("TRUE")) {
                active.add(sku);
            }
""",
        '"INVALID_VARIANT_STATUS"',
        "rechazar estados de variante desconocidos",
    )

    content = replace_unless_applied(
        content,
        """    private void validateImages(JsonNode images) {
        int primary = 0;
        for (JsonNode image : images) {
            String storageKey = firstText(image, "storageKey", "storage_key").replace('\\\\', '/');
""",
        """    private void validateImages(JsonNode images, Set<String> allSkus) {
        int primary = 0;
        for (JsonNode image : images) {
            String sku = text(image, "sku").toUpperCase(Locale.ROOT);
            if (!sku.isBlank() && !allSkus.contains(sku)) {
                throw new BusinessRuleException(
                        "INVALID_PRODUCT_REFERENCE",
                        "Una imagen referencia el SKU inexistente " + sku + ".");
            }
            String storageKey = firstText(image, "storageKey", "storage_key").replace('\\\\', '/');
""",
        "private void validateImages(JsonNode images, Set<String> allSkus)",
        "rechazar imágenes con SKU inexistente",
    )

    backup(PROJECTION)
    write(PROJECTION, content)


def remove_temporary_guard() -> None:
    content = read(VALIDATOR)
    original = content

    guard = """        if (hasUnresolvedImportedAttributes(aggregate)) {
            messages.add(
                    "La fila contiene atributos del Excel, pero todavía no fueron resueltos "
                            + "contra categoria_atributos ni proyectados en attributeValues. "
                            + "No confirmes esta fila hasta implementar el resolvedor de atributos.");
        }
"""
    content = content.replace(guard, "")

    method_pattern = re.compile(
        r"\n    private boolean hasUnresolvedImportedAttributes\(ObjectNode aggregate\) \{"
        r".*?\n    \}\n",
        re.DOTALL,
    )
    content, count = method_pattern.subn("\n", content, count=1)

    if content == original:
        print("No había guarda temporal de atributos que retirar.")
        return

    backup(VALIDATOR)
    write(VALIDATOR, content)
    print(f"Retirada la guarda temporal de atributos ({count} método eliminado).")


def patch_executor() -> None:
    content = read(EXECUTOR)

    content = replace_unless_applied(
        content,
        """        aggregate.put("productId", productId);
        aggregate = imageService.materialize(importItem, productId, aggregate);
""",
        """        aggregate.put("productId", productId);
        bindNestedProductId(aggregate, productId);
        aggregate = imageService.materialize(importItem, productId, aggregate);
""",
        "bindNestedProductId(aggregate, productId);",
        "propagar productId a atributos y ejes",
    )

    content = replace_unless_applied(
        content,
        """    private ObjectNode readObject(String json) {
""",
        """    private void bindNestedProductId(ObjectNode aggregate, String productId) {
        for (JsonNode raw : aggregate.path("attributeValues")) {
            if (raw instanceof ObjectNode value) value.put("producto_id", productId);
        }
        for (JsonNode raw : aggregate.path("familyAxes")) {
            if (raw instanceof ObjectNode axis) axis.put("producto_id", productId);
        }
        for (JsonNode raw : aggregate.path("attributeOptions")) {
            if (raw instanceof ObjectNode option) option.put("producto_id", productId);
        }
    }

    private ObjectNode readObject(String json) {
""",
        "private void bindNestedProductId(",
        "agregar propagación de productId",
    )

    backup(EXECUTOR)
    write(EXECUTOR, content)


def main() -> None:
    print(f"Backend detectado: {BACKEND}")
    print(f"Copias de seguridad: {BACKUP_ROOT.relative_to(BACKEND)}")

    replace_resolver()
    patch_parser()
    patch_projection()
    remove_temporary_guard()
    patch_executor()

    print("\nCorrección completa de importación de productos aplicada.")
    print("Incluye:")
    print("  - resolución de atributos heredados por categoría;")
    print("  - tipos texto, número, número+unidad, listas y SI/NO;")
    print("  - opciones y unidades permitidas;")
    print("  - atributos obligatorios y NivelCaptura;")
    print("  - ejes MATRIX;")
    print("  - IDs reales de listas de precios;")
    print("  - validación de familias, versiones, estados e imágenes.")
    print("\nEjecuta en PowerShell:")
    print("  .\\mvnw.cmd clean test")
    print("\nDespués descarga una plantilla nueva:")
    print("  /admin/products/import/template")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"\nERROR: {exception}", file=sys.stderr)
        raise
