package com.abdul.catalogo.product.dto;

import com.abdul.catalogo.product.model.ProductStatus;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductUpsertRequest(
        @NotBlank @Size(max = 100) String code,
        @NotBlank @Size(max = 250) String name,
        @Size(max = 2000) String description,
        @Size(max = 160) String company,
        @Size(max = 160) String brand,
        @Size(max = 160) String category,
        @NotNull ProductStatus status,
        @Min(0) Long version,
        JsonNode details
) {
}
