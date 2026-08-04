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
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    public ProductProjectionService(ProductRepository productRepository, ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
    }

    public void validateUpsert(String productId, JsonNode payload) {
        JsonNode aggregate = aggregate(payload);
        String code = text(aggregate, "code", "codigo");
        String name = text(aggregate, "name", "nombre");
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
        } else {
            JsonNode aggregate = aggregate(payload);
            product.setCode(text(aggregate, "code", "codigo"));
            product.setName(text(aggregate, "name", "nombre"));
            product.setDescription(text(aggregate, "description", "descripcion"));
            product.setCompany(text(aggregate, "company", "empresa"));
            product.setCompanyId(text(aggregate, "companyId", "empresaId"));
            product.setBrand(text(aggregate, "brand", "marca"));
            product.setBrandId(text(aggregate, "brandId", "marcaId"));
            product.setCategory(text(aggregate, "category", "categoria"));
            product.setCategoryId(text(aggregate, "categoryId", "categoriaId"));
            product.setSubcategory(text(aggregate, "subcategory", "subcategoria"));
            product.setSubcategoryId(text(aggregate, "subcategoryId", "subcategoriaId"));
            product.setProductType(productType(aggregate));
            product.setStatus(status(aggregate));
            product.setDeletedAt(null);
            product.setAggregateJson(write(aggregate));
        }
        productRepository.save(product);
    }

    private void validateAggregateCollections(JsonNode aggregate) {
        JsonNode variants = aggregate.get("variants");
        if (variants == null || !variants.isArray() || variants.isEmpty()) {
            throw new BusinessRuleException("PRODUCT_VARIANTS_REQUIRED", "El agregado debe contener al menos una variante.");
        }
        Set<String> skus = new HashSet<>();
        for (JsonNode variant : variants) {
            String sku = text(variant, "sku", "codigo");
            if (sku.isBlank() || !skus.add(sku.toUpperCase())) {
                throw new BusinessRuleException("INVALID_PRODUCT_VARIANT", "Cada variante debe tener un SKU único.");
            }
        }
        requireArray(aggregate, "presentations");
        requireArray(aggregate, "prices");
        requireArray(aggregate, "images");
        JsonNode attributes = aggregate.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            throw new BusinessRuleException("INVALID_PRODUCT_ATTRIBUTES", "attributes debe ser un objeto JSON.");
        }
        validateReferences(aggregate.get("presentations"), skus, "sku", "La presentación referencia un SKU inexistente.");
        validateReferences(aggregate.get("prices"), skus, "sku", "El precio referencia un SKU inexistente.");
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
        JsonNode nested = payload == null ? null : payload.get("product");
        return nested != null && nested.isObject() ? nested : payload;
    }

    private ProductType productType(JsonNode node) {
        try {
            return ProductType.valueOf(node.path("productType").asText("SINGLE").toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_TYPE", "Tipo de producto no reconocido.");
        }
    }

    private ProductStatus status(JsonNode node) {
        String value = text(node, "status", "estado");
        if (!value.isBlank()) {
            try {
                return ProductStatus.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Compatibilidad con el campo booleano de la app móvil.
            }
        }
        JsonNode active = node.get("active");
        if (active == null) active = node.get("activo");
        return active != null && !active.asBoolean(true) ? ProductStatus.INACTIVE : ProductStatus.ACTIVE;
    }

    private String text(JsonNode node, String primary, String legacy) {
        if (node == null) return "";
        JsonNode value = node.get(primary);
        if (value == null || value.isNull()) value = node.get(legacy);
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
