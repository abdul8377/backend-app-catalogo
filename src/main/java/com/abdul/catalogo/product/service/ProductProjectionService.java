package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.entity.ProductEntity;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
    }

    public void apply(String productId, JsonNode payload, long version, String originDeviceId, boolean deleted) {
        ProductEntity product = productRepository.findById(productId).orElseGet(ProductEntity::new);
        product.setId(productId);
        product.setVersion(version);
        product.setOriginDeviceId(originDeviceId);
        if (deleted) {
            product.setStatus(ProductStatus.DELETED);
            product.setDeletedAt(Instant.now());
            if (product.getCode() == null) {
                product.setCode("deleted-" + productId);
                product.setName("Producto eliminado");
                product.setDescription("");
                product.setCompany("");
                product.setBrand("");
                product.setCategory("");
                product.setAggregateJson("{}");
            }
        } else {
            JsonNode aggregate = aggregate(payload);
            product.setCode(text(aggregate, "code", "codigo"));
            product.setName(text(aggregate, "name", "nombre"));
            product.setDescription(text(aggregate, "description", "descripcion"));
            product.setCompany(text(aggregate, "company", "empresa"));
            product.setBrand(text(aggregate, "brand", "marca"));
            product.setCategory(text(aggregate, "category", "categoria"));
            product.setStatus(status(aggregate));
            product.setDeletedAt(null);
            product.setAggregateJson(write(aggregate));
        }
        productRepository.save(product);
    }

    private JsonNode aggregate(JsonNode payload) {
        JsonNode nested = payload == null ? null : payload.get("product");
        return nested != null && nested.isObject() ? nested : payload;
    }

    private ProductStatus status(JsonNode node) {
        String value = text(node, "status", "estado");
        if (!value.isBlank()) {
            try {
                return ProductStatus.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Flutter currently sends activo as a boolean; unknown textual values remain active.
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
