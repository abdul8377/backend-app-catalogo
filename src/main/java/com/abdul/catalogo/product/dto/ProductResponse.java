package com.abdul.catalogo.product.dto;

import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record ProductResponse(
        String id,
        String code,
        String name,
        String description,
        String company,
        String companyId,
        String brand,
        String brandId,
        String category,
        String categoryId,
        String subcategory,
        String subcategoryId,
        ProductType productType,
        ProductStatus status,
        long version,
        JsonNode aggregate,
        Instant createdAt,
        Instant updatedAt
) {
}
