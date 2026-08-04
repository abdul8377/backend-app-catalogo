package com.abdul.catalogo.product.importing.dto;

import com.abdul.catalogo.product.importing.model.ProductImportStatus;

import java.time.Instant;
import java.util.List;

public record ProductImportPreviewResponse(
        String importId,
        String fileName,
        String fileHash,
        ProductImportStatus status,
        int totalRows,
        int validRows,
        int warningRows,
        int errorRows,
        Instant createdAt,
        Instant confirmedAt,
        List<ProductImportRowResponse> rows
) {
}
