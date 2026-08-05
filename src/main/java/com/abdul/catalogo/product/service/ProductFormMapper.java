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

    public ObjectNode aggregate(ProductForm form) {
        ObjectNode aggregate = objectMapper.createObjectNode();
        aggregate.put("productId", safe(form.getProductId()));
        aggregate.put("code", safe(form.getCode()).toUpperCase());
        aggregate.put("name", safe(form.getName()));
        aggregate.put("description", safe(form.getDescription()));
        aggregate.put("company", safe(form.getCompany()));
        aggregate.put("companyId", safe(form.getCompanyId()));
        aggregate.put("brand", safe(form.getBrand()));
        aggregate.put("brandId", safe(form.getBrandId()));
        aggregate.put("category", safe(form.getCategory()));
        aggregate.put("categoryId", safe(form.getCategoryId()));
        aggregate.put("subcategory", safe(form.getSubcategory()));
        aggregate.put("subcategoryId", safe(form.getSubcategoryId()));
        aggregate.put("productType", form.getProductType().name());
        aggregate.put("status", form.getStatus().name());

        aggregate.set("attributes", read(form.getAttributesJson(), "attributes", true));
        aggregate.set("variants", read(form.getVariantsJson(), "variants", false));
        aggregate.set("presentations", read(form.getPresentationsJson(), "presentations", false));
        aggregate.set("prices", read(form.getPricesJson(), "prices", false));
        aggregate.set("images", read(form.getImagesJson(), "images", false));
        aggregate.set("salesConfiguration", read(form.getSalesConfigurationJson(), "salesConfiguration", true));
        aggregate.set("pricingConfiguration", read(form.getPricingConfigurationJson(), "pricingConfiguration", true));
        aggregate.set("imageConfiguration", read(form.getImageConfigurationJson(), "imageConfiguration", true));
        aggregate.set("familyAxes", read(form.getFamilyAxesJson(), "familyAxes", false));
        aggregate.set("attributeValues", read(form.getAttributeValuesJson(), "attributeValues", false));
        aggregate.set("attributeOptions", read(form.getAttributeOptionsJson(), "attributeOptions", false));
        return aggregate;
    }

    public ObjectNode details(ProductForm form) {
        ObjectNode aggregate = aggregate(form);
        ObjectNode details = objectMapper.createObjectNode();
        for (String field : new String[]{
                "attributes", "variants", "presentations", "prices", "images",
                "salesConfiguration", "pricingConfiguration", "imageConfiguration",
                "familyAxes", "attributeValues", "attributeOptions"}) {
            details.set(field, aggregate.get(field));
        }
        return details;
    }

    public String compact(JsonNode node, String field, boolean object) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null) value = object ? objectMapper.createObjectNode() : objectMapper.createArrayNode();
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo presentar el agregado JSON.", exception);
        }
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
            throw new BusinessRuleException("INVALID_PRODUCT_JSON", field + " contiene datos inválidos.");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
