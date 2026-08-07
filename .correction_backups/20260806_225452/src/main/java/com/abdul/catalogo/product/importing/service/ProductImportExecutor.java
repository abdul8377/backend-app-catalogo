package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.importing.entity.ProductImportEntity;
import com.abdul.catalogo.product.importing.entity.ProductImportRowEntity;
import com.abdul.catalogo.product.importing.model.ProductImportAction;
import com.abdul.catalogo.product.importing.model.ProductImportRowStatus;
import com.abdul.catalogo.product.importing.model.ProductImportStatus;
import com.abdul.catalogo.product.importing.repository.ProductImportRepository;
import com.abdul.catalogo.product.importing.repository.ProductImportRowRepository;
import com.abdul.catalogo.product.service.ProductService;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProductImportExecutor {
    private static final String IMPORT_ORIGIN = "server-excel";
    private final ProductImportRepository importRepository;
    private final ProductImportRowRepository rowRepository;
    private final ProductService productService;
    private final ProductImportImageService imageService;
    private final ObjectMapper objectMapper;

    public ProductImportExecutor(ProductImportRepository importRepository, ProductImportRowRepository rowRepository,
                                 ProductService productService, ProductImportImageService imageService,
                                 ObjectMapper objectMapper) {
        this.importRepository = importRepository;
        this.rowRepository = rowRepository;
        this.productService = productService;
        this.imageService = imageService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<String> prepare(String importId) {
        ProductImportEntity item = importRepository.findForUpdate(importId)
                .orElseThrow(() -> new ResourceNotFoundException("IMPORT_NOT_FOUND", "La importación no existe."));
        if (item.getStatus() == ProductImportStatus.CONFIRMED) return List.of();
        if (item.getStatus() == ProductImportStatus.CONFIRMING) {
            throw new BusinessRuleException("IMPORT_IN_PROGRESS", "La importación ya se está confirmando.");
        }
        if (item.getErrorRows() > 0) {
            throw new BusinessRuleException("IMPORT_HAS_ERRORS", "Corrige las filas con error antes de confirmar.");
        }
        item.setStatus(ProductImportStatus.CONFIRMING);
        return rowRepository.findByImportIdOrderByRowNumber(importId).stream()
                .filter(row -> row.getAction() == ProductImportAction.CREATE || row.getAction() == ProductImportAction.UPDATE)
                .filter(row -> row.getStatus() != ProductImportRowStatus.IMPORTED)
                .map(ProductImportRowEntity::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeRow(String rowId) {
        ProductImportRowEntity row = rowRepository.findById(rowId)
                .orElseThrow(() -> new ResourceNotFoundException("IMPORT_ROW_NOT_FOUND", "La fila de importación no existe."));
        if (row.getStatus() == ProductImportRowStatus.IMPORTED) return;
        ProductImportEntity importItem = importRepository.findById(row.getImportId())
                .orElseThrow(() -> new ResourceNotFoundException("IMPORT_NOT_FOUND", "La importación no existe."));
        ObjectNode aggregate = readObject(row.getCandidateJson());
        String productId = row.getAction() == ProductImportAction.CREATE
                ? aggregate.path("productId").asText(UUID.randomUUID().toString())
                : row.getProductId();
        if (productId == null || productId.isBlank()) productId = UUID.randomUUID().toString();
        aggregate.put("productId", productId);
        bindNestedProductId(aggregate, productId);
        aggregate = imageService.materialize(importItem, productId, aggregate);

        ProductResponse result;
        if (row.getAction() == ProductImportAction.CREATE) {
            result = productService.createAggregate(aggregate, IMPORT_ORIGIN);
        } else {
            result = productService.updateAggregate(productId, row.getExpectedVersion(), aggregate, IMPORT_ORIGIN);
        }
        row.setStatus(ProductImportRowStatus.IMPORTED);
        row.setResultProductId(result.id());
        row.setResultVersion(result.version());
        row.setProcessedAt(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String rowId, String message) {
        ProductImportRowEntity row = rowRepository.findById(rowId).orElseThrow();
        row.setStatus(ProductImportRowStatus.FAILED);
        row.setMessagesJson(writeMessages(List.of(message == null ? "Error no especificado" : message)));
        row.setProcessedAt(Instant.now());
    }

    @Transactional
    public void finish(String importId) {
        ProductImportEntity item = importRepository.findForUpdate(importId).orElseThrow();
        boolean failed = rowRepository.findByImportIdOrderByRowNumber(importId).stream()
                .anyMatch(row -> row.getStatus() == ProductImportRowStatus.FAILED);
        item.setStatus(failed ? ProductImportStatus.FAILED : ProductImportStatus.CONFIRMED);
        if (!failed) item.setConfirmedAt(Instant.now());
    }

    private void bindNestedProductId(ObjectNode aggregate, String productId) {
        for (JsonNode raw : aggregate.path("attributeValues")) {
            if (raw instanceof ObjectNode value) value.put("producto_id", productId);
        }
        for (JsonNode raw : aggregate.path("familyAxes")) {
            if (raw instanceof ObjectNode axis) axis.put("producto_id", productId);
        }
        for (JsonNode raw : aggregate.path("attributeOptions")) {
            if (raw instanceof ObjectNode option) option.put("producto_id", productId);
        }
    }

    private ObjectNode readObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return (ObjectNode) node;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BusinessRuleException("INVALID_IMPORT_CANDIDATE", "La fila almacenada contiene JSON inválido.");
        }
    }

    private String writeMessages(List<String> messages) {
        try { return objectMapper.writeValueAsString(messages); }
        catch (JacksonException exception) { return "[\"Error al serializar el mensaje\"]"; }
    }
}
