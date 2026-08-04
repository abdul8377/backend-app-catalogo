package com.abdul.catalogo.product.dto;

import com.abdul.catalogo.product.model.ProductStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record ProductResponse(
        String id,
        String code,
        String name,
        String description,
        String company,
        String brand,
        String category,
        ProductStatus status,
        long version,
        JsonNode aggregate,
        Instant createdAt,
        Instant updatedAt
) {
}
