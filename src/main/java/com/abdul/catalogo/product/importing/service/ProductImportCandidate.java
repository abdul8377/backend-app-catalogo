package com.abdul.catalogo.product.importing.service;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;

public record ProductImportCandidate(
        int sourceRow,
        String familyCode,
        String productId,
        Long expectedVersion,
        ObjectNode aggregate,
        List<String> warnings,
        List<String> errors
) {
}
