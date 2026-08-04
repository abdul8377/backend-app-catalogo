package com.abdul.catalogo.product.importing.dto;

import com.abdul.catalogo.product.importing.model.ProductImportAction;
import com.abdul.catalogo.product.importing.model.ProductImportRowStatus;

import java.util.List;

public record ProductImportRowResponse(
        String rowId,
        int rowNumber,
        String familyCode,
        String productId,
        Long expectedVersion,
        ProductImportAction action,
        ProductImportRowStatus status,
        List<String> messages,
        String resultProductId,
        Long resultVersion
) {
}
