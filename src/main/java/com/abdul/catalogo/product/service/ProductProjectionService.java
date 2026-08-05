package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.entity.ProductEntity;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Service
public class ProductProjectionService {
    private static final Set<String> REQUIRED_PRODUCT_FIELDS = Set.of(
            "productId", "code", "name", "description", "company", "companyId", "brand", "brandId",
            "category", "categoryId", "subcategory", "subcategoryId", "productType", "status",
            "attributes", "variants", "presentations", "prices", "images");

    private static final Set<String> SUPPORTED_PRODUCT_FIELDS = Set.of(
            "productId", "code", "name", "description", "company", "companyId", "brand", "brandId",
            "category", "categoryId", "subcategory", "subcategoryId", "productType", "status",
            "attributes", "variants", "presentations", "prices", "images",
            "familyAxes", "attributeValues", "attributeOptions");

    private final ProductRepository productRepository;
    private final ProductRelationalProjectionService relationalProjectionService;
    private final ObjectMapper objectMapper;

    public ProductProjectionService(ProductRepository productRepository,
                                    ProductRelationalProjectionService relationalProjectionService,
                                    ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.relationalProjectionService = relationalProjectionService;
        this.objectMapper = objectMapper;
    }

    public void validateUpsert(String productId, JsonNode payload) {
        JsonNode aggregate = aggregate(payload);
        if (aggregate == null || !aggregate.isObject()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", "PRODUCT debe ser un objeto JSON completo.");
        }
        Set<String> fields = Set.copyOf(aggregate.propertyNames());
        if (!fields.containsAll(REQUIRED_PRODUCT_FIELDS)) {
            Set<String> missing = new HashSet<>(REQUIRED_PRODUCT_FIELDS);
            missing.removeAll(fields);
            throw new BusinessRuleException("INVALID_PRODUCT_FIELDS",
                    "PRODUCT no contiene los bloques requeridos: " + String.join(", ", missing) + ".");
        }
        if (!SUPPORTED_PRODUCT_FIELDS.containsAll(fields)) {
            Set<String> unsupported = new HashSet<>(fields);
            unsupported.removeAll(SUPPORTED_PRODUCT_FIELDS);
            throw new BusinessRuleException("INVALID_PRODUCT_FIELDS",
                    "PRODUCT contiene campos no soportados: " + String.join(", ", unsupported) + ".");
        }
        if (!productId.equals(aggregate.path("productId").asText())) {
            throw new BusinessRuleException("PRODUCT_ID_MISMATCH", "productId debe coincidir con entityId.");
        }
        requireStringFields(aggregate);
        String code = text(aggregate, "code");
        String name = text(aggregate, "name");
        if (code.isBlank() || name.isBlank()) {
            throw new BusinessRuleException("INVALID_PRODUCT_PAYLOAD", "El producto debe incluir código y nombre.");
        }
        boolean duplicate = productRepository.findById(productId).isPresent()
                ? productRepository.existsByCodeIgnoreCaseAndIdNot(code, productId)
                : productRepository.existsByCodeIgnoreCase(code);
        if (duplicate) {
            throw new BusinessRuleException("DUPLICATE_PRODUCT_CODE", "Ya existe otro producto con el código " + code + ".");
        }
        validateAggregateCollections(aggregate);
    }

    public void apply(String productId, JsonNode payload, long version, String originDeviceId, boolean deleted) {
        ProductEntity product = productRepository.findById(productId).orElseGet(ProductEntity::new);
        product.setId(productId);
        product.setVersion(version);
        product.setOriginDeviceId(originDeviceId);
        if (deleted) {
            product.setStatus(ProductStatus.DELETED);
            product.setDeletedAt(Instant.now());
            if (product.getCode() == null) initializeDeleted(product, productId);
            productRepository.saveAndFlush(product);
            relationalProjectionService.clear(productId);
            return;
        }

        JsonNode aggregate = aggregate(payload);
        product.setCode(text(aggregate, "code"));
        product.setName(text(aggregate, "name"));
        product.setDescription(text(aggregate, "description"));
        product.setCompany(text(aggregate, "company"));
        product.setCompanyId(text(aggregate, "companyId"));
        product.setBrand(text(aggregate, "brand"));
        product.setBrandId(text(aggregate, "brandId"));
        product.setCategory(text(aggregate, "category"));
        product.setCategoryId(text(aggregate, "categoryId"));
        product.setSubcategory(text(aggregate, "subcategory"));
        product.setSubcategoryId(text(aggregate, "subcategoryId"));
        product.setProductType(productType(aggregate));
        product.setStatus(status(aggregate));
        product.setDeletedAt(null);
        product.setAggregateJson(write(aggregate));
        productRepository.saveAndFlush(product);
        relationalProjectionService.replace(productId, aggregate);
    }

    private void validateAggregateCollections(JsonNode aggregate) {
        JsonNode variants = aggregate.get("variants");
        if (variants == null || !variants.isArray() || variants.isEmpty()) {
            throw new BusinessRuleException("PRODUCT_VARIANTS_REQUIRED", "El agregado debe contener al menos una variante.");
        }
        Set<String> skus = new HashSet<>();
        for (JsonNode variant : variants) {
            String sku = text(variant, "sku");
            if (sku.isBlank() || !skus.add(sku.toUpperCase())) {
                throw new BusinessRuleException("INVALID_PRODUCT_VARIANT", "Cada variante debe tener un SKU único.");
            }
        }
        requireArray(aggregate, "presentations");
        requireArray(aggregate, "prices");
        requireArray(aggregate, "images");
        requireOptionalArray(aggregate, "familyAxes");
        requireOptionalArray(aggregate, "attributeValues");
        requireOptionalArray(aggregate, "attributeOptions");
        JsonNode attributes = aggregate.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            throw new BusinessRuleException("INVALID_PRODUCT_ATTRIBUTES", "attributes debe ser un objeto JSON.");
        }
        validateReferences(aggregate.get("presentations"), skus, "sku", "La presentación referencia un SKU inexistente.");
        validateReferences(aggregate.get("prices"), skus, "sku", "El precio referencia un SKU inexistente.");
        validateImageStorageKeys(aggregate.get("images"));
    }

    private void validateImageStorageKeys(JsonNode images) {
        for (JsonNode image : images) {
            String storageKey = image.path("storageKey").asText("").trim().replace('\\', '/');
            if (storageKey.isBlank() || storageKey.startsWith("/") || storageKey.contains(":")
                    || storageKey.equals("..") || storageKey.contains("../") || !storageKey.startsWith("files/")) {
                throw new BusinessRuleException("INVALID_PRODUCT_IMAGE_KEY",
                        "Cada imagen debe usar un storageKey relativo generado por el backend.");
            }
        }
    }

    private void validateReferences(JsonNode rows, Set<String> skus, String field, String message) {
        for (JsonNode row : rows) {
            String sku = row.path(field).asText("").trim();
            if (!sku.isBlank() && !skus.contains(sku.toUpperCase())) {
                throw new BusinessRuleException("INVALID_PRODUCT_REFERENCE", message + " SKU=" + sku);
            }
        }
    }

    private void requireArray(JsonNode aggregate, String field) {
        JsonNode value = aggregate.get(field);
        if (value == null || !value.isArray()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", field + " debe ser un arreglo JSON.");
        }
    }

    private void requireOptionalArray(JsonNode aggregate, String field) {
        JsonNode value = aggregate.get(field);
        if (value != null && !value.isArray()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", field + " debe ser un arreglo JSON.");
        }
    }

    private void initializeDeleted(ProductEntity product, String productId) {
        product.setCode("deleted-" + productId);
        product.setName("Producto eliminado");
        product.setDescription("");
        product.setCompany("");
        product.setCompanyId("");
        product.setBrand("");
        product.setBrandId("");
        product.setCategory("");
        product.setCategoryId("");
        product.setSubcategory("");
        product.setSubcategoryId("");
        product.setProductType(ProductType.SINGLE);
        product.setAggregateJson("{}");
    }

    private JsonNode aggregate(JsonNode payload) {
        return payload;
    }

    private ProductType productType(JsonNode node) {
        try {
            return ProductType.valueOf(node.path("productType").asText("SINGLE").toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_TYPE", "Tipo de producto no reconocido.");
        }
    }

    private ProductStatus status(JsonNode node) {
        String value = text(node, "status");
        try {
            return ProductStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_STATUS", "Estado de producto no reconocido.");
        }
    }

    private void requireStringFields(JsonNode node) {
        for (String field : Set.of("productId", "code", "name", "description", "company", "companyId", "brand",
                "brandId", "category", "categoryId", "subcategory", "subcategoryId", "productType", "status")) {
            if (!node.path(field).isString()) {
                throw new BusinessRuleException("INVALID_PRODUCT_FIELD_TYPE", field + " debe ser texto.");
            }
        }
    }

    private String text(JsonNode node, String primary) {
        if (node == null) return "";
        JsonNode value = node.get(primary);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_JSON", "No se pudo guardar el agregado del producto.");
        }
    }
}
