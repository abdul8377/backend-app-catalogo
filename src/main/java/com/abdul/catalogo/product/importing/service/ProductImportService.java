package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.product.importing.dto.ProductImportPreviewResponse;
import com.abdul.catalogo.product.importing.dto.ProductImportRowResponse;
import com.abdul.catalogo.product.importing.entity.ProductImportEntity;
import com.abdul.catalogo.product.importing.entity.ProductImportRowEntity;
import com.abdul.catalogo.product.importing.model.ProductImportAction;
import com.abdul.catalogo.product.importing.model.ProductImportRowStatus;
import com.abdul.catalogo.product.importing.model.ProductImportStatus;
import com.abdul.catalogo.product.importing.repository.ProductImportRepository;
import com.abdul.catalogo.product.importing.repository.ProductImportRowRepository;
import com.abdul.catalogo.shared.config.ProductImportProperties;
import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.storage.StorageService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductImportService {
    public static final String TEMPLATE_VERSION = "1.0";
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "");

    private final ProductImportProperties properties;
    private final ProductWorkbookParser parser;
    private final ProductImportValidator validator;
    private final ProductImportRepository importRepository;
    private final ProductImportRowRepository rowRepository;
    private final ProductImportExecutor executor;
    private final ProductImportReportService reportService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public ProductImportService(ProductImportProperties properties, ProductWorkbookParser parser,
                                ProductImportValidator validator, ProductImportRepository importRepository,
                                ProductImportRowRepository rowRepository, ProductImportExecutor executor,
                                ProductImportReportService reportService, StorageService storageService,
                                ObjectMapper objectMapper) {
        this.properties = properties; this.parser = parser; this.validator = validator;
        this.importRepository = importRepository; this.rowRepository = rowRepository; this.executor = executor;
        this.reportService = reportService; this.storageService = storageService; this.objectMapper = objectMapper;
    }

    @Transactional
    public ProductImportPreviewResponse preview(MultipartFile file, String actor) {
        byte[] bytes = validateAndRead(file);
        String hash = Digests.sha256(bytes);
        ProductImportEntity existing = importRepository.findByFileHash(hash).orElse(null);
        if (existing != null) return toResponse(existing);

        String importId = UUID.randomUUID().toString();
        String fileName = safeName(file.getOriginalFilename());
        String storageKey;
        try {
            storageKey = storageService.store("imports/" + importId + "/original.xlsx",
                    new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (IOException exception) {
            throw new BusinessRuleException("IMPORT_STORAGE_ERROR", "No se pudo guardar el original de la importación.");
        }

        ProductImportEntity item = new ProductImportEntity();
        item.setId(importId); item.setFileName(fileName); item.setFileHash(hash); item.setStorageKey(storageKey);
        item.setTemplateVersion(TEMPLATE_VERSION); item.setStatus(ProductImportStatus.PREVIEW_READY);
        item.setCreatedBy(actor); item.setCreatedAt(Instant.now());

        List<ProductImportRowEntity> rows = new ArrayList<>();
        int valid = 0, warning = 0, error = 0;
        List<ProductImportCandidate> candidates;
        try {
            candidates = parser.parse(bytes);
        } catch (BusinessRuleException exception) {
            ProductImportRowEntity row = new ProductImportRowEntity();
            row.setId(UUID.randomUUID().toString()); row.setImportId(importId); row.setRowNumber(0);
            row.setFamilyCode("(archivo)"); row.setAction(ProductImportAction.REJECT);
            row.setStatus(ProductImportRowStatus.ERROR); row.setCandidateJson("{}");
            row.setMessagesJson(write(List.of(exception.getMessage()))); rows.add(row); error = 1;
            candidates = List.of();
        }
        for (ProductImportCandidate candidate : candidates) {
            ProductImportValidator.ValidationResult validation = validator.validate(candidate);
            ProductImportRowEntity row = new ProductImportRowEntity();
            row.setId(UUID.randomUUID().toString()); row.setImportId(importId); row.setRowNumber(candidate.sourceRow());
            row.setFamilyCode(candidate.familyCode()); row.setProductId(validation.productId());
            row.setExpectedVersion(validation.expectedVersion()); row.setAction(validation.action()); row.setStatus(validation.status());
            row.setCandidateJson(write(candidate.aggregate())); row.setMessagesJson(write(validation.messages())); rows.add(row);
            if (validation.status() == ProductImportRowStatus.ERROR) error++;
            else if (validation.status() == ProductImportRowStatus.WARNING) warning++;
            else valid++;
        }
        item.setTotalRows(rows.size()); item.setValidRows(valid); item.setWarningRows(warning); item.setErrorRows(error);
        importRepository.save(item); rowRepository.saveAll(rows);
        return toResponse(item);
    }

    public ProductImportPreviewResponse confirm(String importId) {
        List<String> rows = executor.prepare(importId);
        for (String rowId : rows) {
            try { executor.executeRow(rowId); }
            catch (RuntimeException exception) { executor.markFailed(rowId, rootMessage(exception)); }
        }
        executor.finish(importId);
        return get(importId);
    }

    @Transactional(readOnly = true)
    public ProductImportPreviewResponse get(String importId) {
        return toResponse(importRepository.findById(importId)
                .orElseThrow(() -> new ResourceNotFoundException("IMPORT_NOT_FOUND", "La importación no existe.")));
    }

    @Transactional(readOnly = true)
    public byte[] report(String importId) {
        ProductImportEntity item = importRepository.findById(importId)
                .orElseThrow(() -> new ResourceNotFoundException("IMPORT_NOT_FOUND", "La importación no existe."));
        return reportService.generate(item, rowRepository.findByImportIdOrderByRowNumber(importId));
    }

    public byte[] template() {
        try { return new ClassPathResource("excel/product-import-v1.xlsx").getInputStream().readAllBytes(); }
        catch (IOException exception) { throw new IllegalStateException("No se encontró la plantilla Excel versionada.", exception); }
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessRuleException("EMPTY_IMPORT_FILE", "Selecciona un archivo XLSX.");
        if (file.getSize() > properties.maxFileSizeBytes()) throw new BusinessRuleException("IMPORT_FILE_TOO_LARGE", "El XLSX supera el tamaño permitido.");
        String name = safeName(file.getOriginalFilename());
        if (!name.toLowerCase().endsWith(".xlsx")) throw new BusinessRuleException("INVALID_IMPORT_EXTENSION", "Solo se aceptan archivos .xlsx sin macros.");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) throw new BusinessRuleException("INVALID_IMPORT_MIME", "El tipo MIME del archivo no está permitido.");
        try { return file.getBytes(); }
        catch (IOException exception) { throw new BusinessRuleException("IMPORT_READ_ERROR", "No se pudo leer el archivo recibido."); }
    }

    private ProductImportPreviewResponse toResponse(ProductImportEntity item) {
        List<ProductImportRowResponse> rows = rowRepository.findByImportIdOrderByRowNumber(item.getId()).stream()
                .map(row -> new ProductImportRowResponse(row.getId(), row.getRowNumber(), row.getFamilyCode(),
                        row.getProductId(), row.getExpectedVersion(), row.getAction(), row.getStatus(),
                        messages(row.getMessagesJson()), row.getResultProductId(), row.getResultVersion())).toList();
        return new ProductImportPreviewResponse(item.getId(), item.getFileName(), item.getFileHash(), item.getStatus(),
                item.getTotalRows(), item.getValidRows(), item.getWarningRows(), item.getErrorRows(), item.getCreatedAt(),
                item.getConfirmedAt(), rows);
    }

    private List<String> messages(String json) {
        try {
            JsonNode node = objectMapper.readTree(json); List<String> result = new ArrayList<>();
            if (node != null && node.isArray()) node.forEach(value -> result.add(value.asText()));
            return List.copyOf(result);
        } catch (JacksonException exception) { return List.of("No se pudieron leer los mensajes de validación."); }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("No se pudo serializar la vista previa.", exception); }
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "import.xlsx";
        return Path.of(name.replace('\\', '/')).getFileName().toString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? throwable.getClass().getSimpleName() : current.getMessage();
    }
}
