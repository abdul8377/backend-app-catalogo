package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.importing.model.ProductImportAction;
import com.abdul.catalogo.product.importing.model.ProductImportRowStatus;
import com.abdul.catalogo.product.service.ProductProjectionService;
import com.abdul.catalogo.product.service.ProductService;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ProductImportValidator {
    private final ProductService productService;
    private final ProductProjectionService projectionService;

    public ProductImportValidator(ProductService productService, ProductProjectionService projectionService) {
        this.productService = productService;
        this.projectionService = projectionService;
    }

    public ValidationResult validate(ProductImportCandidate candidate) {
        List<String> messages = new ArrayList<>(candidate.errors());
        messages.addAll(candidate.warnings());
        ProductImportAction action = ProductImportAction.CREATE;
        String productId = candidate.productId();
        Long expectedVersion = candidate.expectedVersion();

        if (productId != null) {
            action = ProductImportAction.UPDATE;
            try {
                ProductResponse existing = productService.get(productId);
                if (expectedVersion == null) messages.add("Version es obligatoria cuando se indica ProductoId.");
                else if (expectedVersion != existing.version()) messages.add("La versión indicada ya no es la vigente (" + existing.version() + ").");
            } catch (ResourceNotFoundException exception) {
                messages.add("ProductoId no existe en el servidor.");
            }
        } else if (productService.findByCode(candidate.familyCode()).isPresent()) {
            messages.add("El código ya existe. Para actualizar debe indicar ProductoId y Version; el código solo valida unicidad.");
        }

        ObjectNode aggregate = candidate.aggregate().deepCopy();
        String validationId = productId == null ? UUID.randomUUID().toString() : productId;
        aggregate.put("productId", validationId);
        if (candidate.errors().isEmpty()) {
            try {
                projectionService.validateUpsert(validationId, aggregate);
            } catch (BusinessRuleException exception) {
                messages.add(exception.getMessage());
            }
        }

        int blocking = messages.size() - candidate.warnings().size();
        if (blocking > 0) {
            action = ProductImportAction.REJECT;
            return new ValidationResult(action, ProductImportRowStatus.ERROR, productId, expectedVersion, List.copyOf(messages));
        }
        ProductImportRowStatus status = candidate.warnings().isEmpty()
                ? ProductImportRowStatus.VALID : ProductImportRowStatus.WARNING;
        return new ValidationResult(action, status, productId, expectedVersion, List.copyOf(messages));
    }

    public record ValidationResult(ProductImportAction action, ProductImportRowStatus status,
                                   String productId, Long expectedVersion, List<String> messages) {
    }
}
