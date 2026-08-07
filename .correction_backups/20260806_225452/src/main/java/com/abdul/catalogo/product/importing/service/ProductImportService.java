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
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductImportService {
    public static final String TEMPLATE_VERSION = "2.1";
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "");

    private final ProductImportProperties properties;
    private final ProductWorkbookParser parser;
    private final ProductMasterDataResolver masterDataResolver;
    private final ProductImportReferenceSheets referenceSheets;
    private final ProductImportValidator validator;
    private final ProductImportImageService imageService;
    private final ProductImportRepository importRepository;
    private final ProductImportRowRepository rowRepository;
    private final ProductImportExecutor executor;
    private final ProductImportReportService reportService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public ProductImportService(ProductImportProperties properties, ProductWorkbookParser parser,
                                ProductMasterDataResolver masterDataResolver,
                                ProductImportReferenceSheets referenceSheets,
                                ProductImportValidator validator, ProductImportImageService imageService,
                                ProductImportRepository importRepository, ProductImportRowRepository rowRepository,
                                ProductImportExecutor executor, ProductImportReportService reportService,
                                StorageService storageService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.parser = parser;
        this.masterDataResolver = masterDataResolver;
        this.referenceSheets = referenceSheets;
        this.validator = validator;
        this.imageService = imageService;
        this.importRepository = importRepository;
        this.rowRepository = rowRepository;
        this.executor = executor;
        this.reportService = reportService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    public ProductImportPreviewResponse preview(MultipartFile file, String actor) {
        return preview(file, null, actor);
    }

    @Transactional
    public ProductImportPreviewResponse preview(MultipartFile file, MultipartFile imageArchive, String actor) {
        byte[] bytes = validateAndRead(file);
        byte[] zipBytes = readOptionalZip(imageArchive);
        String hash = combinedHash(bytes, zipBytes);
        ProductImportEntity existing = importRepository.findByFileHash(hash).orElse(null);
        if (existing != null) return toResponse(existing);

        Set<String> imageEntries = imageService.inspect(zipBytes);
        String importId = UUID.randomUUID().toString();
        String fileName = safeName(file.getOriginalFilename());
        String storageKey = store("imports/" + importId + "/original.xlsx", bytes,
                "No se pudo guardar el original de la importación.");

        ProductImportEntity item = new ProductImportEntity();
        item.setId(importId);
        item.setFileName(fileName);
        item.setFileHash(hash);
        item.setStorageKey(storageKey);
        if (zipBytes.length > 0) {
            item.setImageArchiveName(safeName(imageArchive.getOriginalFilename()));
            item.setImageArchiveHash(Digests.sha256(zipBytes));
            item.setImageArchiveStorageKey(store("imports/" + importId + "/images.zip", zipBytes,
                    "No se pudo guardar el ZIP de imágenes."));
        }
        item.setTemplateVersion(TEMPLATE_VERSION);
        item.setStatus(ProductImportStatus.PREVIEW_READY);
        item.setCreatedBy(actor);
        item.setCreatedAt(Instant.now());

        List<ProductImportRowEntity> rows = new ArrayList<>();
        int valid = 0, warning = 0, error = 0;
        List<ProductImportCandidate> candidates;
        try {
            candidates = parser.parse(bytes, imageEntries).stream()
                    .map(masterDataResolver::resolve)
                    .toList();
        } catch (BusinessRuleException exception) {
            ProductImportRowEntity row = new ProductImportRowEntity();
            row.setId(UUID.randomUUID().toString());
            row.setImportId(importId);
            row.setRowNumber(0);
            row.setFamilyCode("(archivo)");
            row.setAction(ProductImportAction.REJECT);
            row.setStatus(ProductImportRowStatus.ERROR);
            row.setCandidateJson("{}");
            row.setMessagesJson(write(List.of(exception.getMessage())));
            rows.add(row);
            error = 1;
            candidates = List.of();
        }
        for (ProductImportCandidate candidate : candidates) {
            ProductImportValidator.ValidationResult validation = validator.validate(candidate);
            ProductImportRowEntity row = new ProductImportRowEntity();
            row.setId(UUID.randomUUID().toString());
            row.setImportId(importId);
            row.setRowNumber(candidate.sourceRow());
            row.setFamilyCode(candidate.familyCode());
            row.setProductId(validation.productId());
            row.setExpectedVersion(validation.expectedVersion());
            row.setAction(validation.action());
            row.setStatus(validation.status());
            row.setCandidateJson(write(candidate.aggregate()));
            row.setMessagesJson(write(validation.messages()));
            rows.add(row);
            if (validation.status() == ProductImportRowStatus.ERROR) error++;
            else if (validation.status() == ProductImportRowStatus.WARNING) warning++;
            else valid++;
        }
        item.setTotalRows(rows.size());
        item.setValidRows(valid);
        item.setWarningRows(warning);
        item.setErrorRows(error);
        importRepository.save(item);
        rowRepository.saveAll(rows);
        return toResponse(item);
    }

    public ProductImportPreviewResponse confirm(String importId) {
        List<String> rows = executor.prepare(importId);
        for (String rowId : rows) {
            try {
                executor.executeRow(rowId);
            } catch (RuntimeException exception) {
                executor.markFailed(rowId, rootMessage(exception));
            }
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
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GOLD.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            header.setFont(font);

            sheet(workbook, header, "Fuentes", "Lote", "ArchivoPDF", "Seccion", "PaginaDesde", "PaginaHasta", "Observacion");
            sheet(workbook, header, "Productos", "CodigoFamilia", "ProductoId", "Version", "Nombre", "Descripcion",
                    "Empresa", "EmpresaId", "Marca", "MarcaId", "Categoria", "CategoriaId", "Subcategoria",
                    "SubcategoriaId", "Tipo", "Estado");
            sheet(workbook, header, "Variantes", "CodigoFamilia", "SKU", "CodigoProveedor", "NombreCorto", "Estado");
            sheet(workbook, header, "Atributos", "CodigoFamilia", "SKU", "Atributo", "Valor", "Unidad");
            sheet(workbook, header, "Presentaciones", "CodigoFamilia", "SKU", "Presentacion", "UnidadBase",
                    "Equivalencia", "VentaMinima", "Incremento", "PermiteDecimales", "Estado");
            sheet(workbook, header, "Precios", "CodigoFamilia", "SKU", "ListaPrecio", "Presentacion", "Moneda",
                    "IGV", "Configuracion", "Precio", "Cotizar");
            sheet(workbook, header, "Imagenes", "CodigoFamilia", "SKU", "Archivo", "Tipo", "Principal");
            Sheet help = sheet(workbook, header, "Instrucciones", "Tema", "Detalle");
            addHelp(help, "Alcance", "La plantilla importa productos y sus componentes. Empresas, marcas, categorías, atributos, unidades y listas se cargan primero desde Datos maestros.");
            addHelp(help, "Identidad", "CodigoFamilia agrupa todas las hojas. Los IDs maestros pueden dejarse vacíos: el servidor los resuelve por nombre y jerarquía.");
            addHelp(help, "Atributos", "SKU vacío = atributo común de la familia. Con SKU = atributo técnico de esa variante.");
            addHelp(help, "Presentaciones", "SKU vacío aplica a todas las variantes activas; también puede repetir una presentación para SKU concretos.");
            addHelp(help, "Precios", "La moneda es siempre PEN. Use Configuracion=precio_fijo con Precio, o Cotizar=SI. La lista debe existir en Datos maestros.");
            addHelp(help, "Imágenes", "Archivo es la ruta relativa exacta dentro del ZIP opcional, por ejemplo pernos/FYB819295.webp.");
            addHelp(help, "PDF", "Registre en Fuentes el archivo, sección y páginas para comprobar que ningún bloque quedó sin procesar.");
            referenceSheets.append(workbook, header);

            workbook.setActiveSheet(workbook.getSheetIndex("Productos"));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar la plantilla Excel.", exception);
        }
    }

    private Sheet sheet(XSSFWorkbook workbook, CellStyle headerStyle, String name, String... headers) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            var cell = row.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, Math.min(60, Math.max(15, headers[index].length() + 4)) * 256);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, Math.max(0, headers.length - 1)));
        return sheet;
    }

    private void addHelp(Sheet sheet, String topic, String detail) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        row.createCell(0).setCellValue(topic);
        row.createCell(1).setCellValue(detail);
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("EMPTY_IMPORT_FILE", "Selecciona un archivo XLSX.");
        }
        if (file.getSize() > properties.maxFileSizeBytes()) {
            throw new BusinessRuleException("IMPORT_FILE_TOO_LARGE", "El XLSX supera el tamaño permitido.");
        }
        String name = safeName(file.getOriginalFilename());
        if (!name.toLowerCase().endsWith(".xlsx")) {
            throw new BusinessRuleException("INVALID_IMPORT_EXTENSION", "Solo se aceptan archivos .xlsx sin macros.");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BusinessRuleException("INVALID_IMPORT_MIME", "El tipo MIME del archivo no está permitido.");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessRuleException("IMPORT_READ_ERROR", "No se pudo leer el archivo recibido.");
        }
    }

    private byte[] readOptionalZip(MultipartFile archive) {
        if (archive == null || archive.isEmpty()) return new byte[0];
        String name = safeName(archive.getOriginalFilename());
        if (!name.toLowerCase().endsWith(".zip")) {
            throw new BusinessRuleException("INVALID_IMAGE_ARCHIVE_EXTENSION", "Las imágenes masivas deben enviarse en un archivo .zip.");
        }
        if (archive.getSize() > 250L * 1024L * 1024L) {
            throw new BusinessRuleException("IMAGE_ARCHIVE_TOO_LARGE", "El ZIP de imágenes supera 250 MB.");
        }
        try {
            return archive.getBytes();
        } catch (IOException exception) {
            throw new BusinessRuleException("IMAGE_ARCHIVE_READ_ERROR", "No se pudo leer el ZIP de imágenes.");
        }
    }

    private String store(String key, byte[] bytes, String message) {
        try {
            return storageService.store(key, new ByteArrayInputStream(bytes), bytes.length);
        } catch (IOException exception) {
            throw new BusinessRuleException("IMPORT_STORAGE_ERROR", message);
        }
    }

    private String combinedHash(byte[] workbook, byte[] zip) {
        byte[] separator = "::IMAGES::".getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[workbook.length + separator.length + zip.length];
        System.arraycopy(workbook, 0, combined, 0, workbook.length);
        System.arraycopy(separator, 0, combined, workbook.length, separator.length);
        System.arraycopy(zip, 0, combined, workbook.length + separator.length, zip.length);
        return Digests.sha256(combined);
    }

    private ProductImportPreviewResponse toResponse(ProductImportEntity item) {
        List<ProductImportRowResponse> rows = rowRepository.findByImportIdOrderByRowNumber(item.getId()).stream()
                .map(row -> new ProductImportRowResponse(row.getId(), row.getRowNumber(), row.getFamilyCode(),
                        row.getProductId(), row.getExpectedVersion(), row.getAction(), row.getStatus(),
                        messages(row.getMessagesJson()), row.getResultProductId(), row.getResultVersion()))
                .toList();
        return new ProductImportPreviewResponse(item.getId(), item.getFileName(), item.getFileHash(), item.getStatus(),
                item.getTotalRows(), item.getValidRows(), item.getWarningRows(), item.getErrorRows(), item.getCreatedAt(),
                item.getConfirmedAt(), rows);
    }

    private List<String> messages(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> result = new ArrayList<>();
            if (node != null && node.isArray()) node.forEach(value -> result.add(value.asText()));
            return List.copyOf(result);
        } catch (JacksonException exception) {
            return List.of("No se pudieron leer los mensajes de validación.");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo serializar la vista previa.", exception);
        }
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "import.xlsx";
        return Path.of(name.replace('\\', '/')).getFileName().toString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? throwable.getClass().getSimpleName() : current.getMessage();
    }
}
