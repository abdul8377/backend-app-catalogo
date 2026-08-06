package com.abdul.catalogo.masterdata.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

@Component
public class MasterDataPayloadValidator {
    private final JdbcTemplate jdbc;

    public MasterDataPayloadValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(String entityType, JsonNode payload, boolean deleted) {
        if (deleted) return;
        String type = entityType == null ? "" : entityType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "COMPANY" -> required(payload, "nombre", "name");
            case "BRAND" -> {
                String companyId = required(payload, "empresa_id", "company_id", "companyId");
                required(payload, "nombre", "name");
                exists("empresas", companyId, "La empresa referenciada por la marca no existe.");
            }
            case "CATEGORY" -> {
                required(payload, "nombre", "name");
                String parentId = optional(payload, "categoria_padre_id", "parent_category_id", "parentCategoryId");
                if (parentId != null) exists("categorias", parentId, "La categoría padre no existe.");
            }
            case "BRAND_CATEGORY" -> {
                String brandId = required(payload, "marca_id", "brand_id", "brandId");
                String categoryId = required(payload, "categoria_id", "category_id", "categoryId");
                exists("marcas", brandId, "La marca de la relación no existe.");
                exists("categorias", categoryId, "La categoría de la relación no existe.");
            }
            case "MEASUREMENT_UNIT" -> {
                required(payload, "codigo", "code");
                required(payload, "nombre", "name");
                required(payload, "simbolo", "symbol");
                required(payload, "magnitud", "magnitude");
            }
            case "CATEGORY_ATTRIBUTE" -> {
                String categoryId = required(payload, "categoria_id", "category_id", "categoryId");
                required(payload, "nombre", "name");
                required(payload, "clave", "key");
                required(payload, "tipo_dato", "data_type", "dataType");
                exists("categorias", categoryId, "La categoría del atributo no existe.");
            }
            case "CATEGORY_ATTRIBUTE_OPTION" -> {
                String attributeId = required(payload, "categoria_atributo_id", "category_attribute_id", "categoryAttributeId");
                required(payload, "etiqueta", "label");
                required(payload, "codigo", "code");
                exists("categoria_atributos", attributeId, "El atributo de la opción no existe.");
            }
            case "CATEGORY_ATTRIBUTE_UNIT" -> {
                String attributeId = required(payload, "categoria_atributo_id", "category_attribute_id", "categoryAttributeId");
                String unitId = required(payload, "unidad_medida_id", "measurement_unit_id", "measurementUnitId");
                exists("categoria_atributos", attributeId, "El atributo de la unidad permitida no existe.");
                exists("unidades_medida", unitId, "La unidad de medida permitida no existe.");
            }
            case "LEGACY_ATTRIBUTE_DEFINITION" -> {
                String categoryId = required(payload, "categoria_id", "category_id", "categoryId");
                required(payload, "nombre", "name");
                required(payload, "tipo", "type");
                exists("categorias", categoryId, "La categoría del atributo heredado no existe.");
            }
            case "PRICE_LIST" -> {
                required(payload, "nombre", "name");
                String currency = optional(payload, "moneda", "currency");
                if (currency != null && !currency.equalsIgnoreCase("PEN")) {
                    throw new BusinessRuleException("PRICE_LIST_CURRENCY",
                            "Las listas de precios solo admiten moneda PEN.");
                }
            }
            default -> {
                // Los tipos no maestros son validados por sus servicios específicos.
            }
        }
    }

    private void exists(String table, String id, String message) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND deleted = FALSE",
                Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessRuleException("MASTER_REFERENCE_NOT_FOUND", message);
        }
    }

    private String required(JsonNode payload, String... aliases) {
        String value = optional(payload, aliases);
        if (value == null) {
            throw new BusinessRuleException("MASTER_REQUIRED_FIELD",
                    "Falta el campo maestro obligatorio " + aliases[0] + ".");
        }
        return value;
    }

    private String optional(JsonNode payload, String... aliases) {
        JsonNode source = payload != null && payload.path("data").isObject()
                ? payload.path("data") : payload;
        if (source == null) return null;
        for (String alias : aliases) {
            JsonNode value = source.get(alias);
            if (value == null || value.isNull()) continue;
            String text = value.asText().trim();
            if (!text.isEmpty()) return text;
        }
        return null;
    }
}
