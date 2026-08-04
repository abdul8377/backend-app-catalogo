package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.web.ProductForm;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ProductFormMapper {
    private final ObjectMapper objectMapper;

    public ProductFormMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode details(ProductForm form) {
        ObjectNode details = objectMapper.createObjectNode();
        details.set("attributes", read(form.getAttributesJson(), "attributes", true));
        details.set("variants", read(form.getVariantsJson(), "variants", false));
        details.set("presentations", read(form.getPresentationsJson(), "presentations", false));
        details.set("prices", read(form.getPricesJson(), "prices", false));
        details.set("images", read(form.getImagesJson(), "images", false));
        return details;
    }

    public String pretty(JsonNode node, String field, boolean object) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null) value = object ? objectMapper.createObjectNode() : objectMapper.createArrayNode();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo presentar el agregado JSON.", exception);
        }
    }

    private JsonNode read(String json, String field, boolean object) {
        try {
            JsonNode value = objectMapper.readTree(json == null || json.isBlank() ? (object ? "{}" : "[]") : json);
            if (value == null || object != value.isObject()) {
                throw new BusinessRuleException("INVALID_PRODUCT_JSON",
                        field + (object ? " debe ser un objeto JSON." : " debe ser un arreglo JSON."));
            }
            return value;
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_JSON", field + " contiene JSON inválido.");
        }
    }
}
