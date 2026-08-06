package com.abdul.catalogo.product;

import com.abdul.catalogo.product.importing.model.ProductImportStatus;
import com.abdul.catalogo.product.importing.service.ProductImportService;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.storage.StoredFileRetentionService;
import com.abdul.catalogo.storage.StoredFileService;
import com.abdul.catalogo.storage.StorageService;
import com.abdul.catalogo.storage.dto.FileIntentRequest;
import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.model.StoredFileType;
import com.abdul.catalogo.storage.repository.StoredFileRepository;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class ProductImportAndStorageIntegrationTest {
    private static final String COMPANY_ID = "10000000-0000-0000-0000-000000000001";
    private static final String BRAND_ID = "10000000-0000-0000-0000-000000000002";
    private static final String CATEGORY_ID = "10000000-0000-0000-0000-000000000003";
    private static final String RELATION_ID = "10000000-0000-0000-0000-000000000004";
    private static final String COMPANY_NAME = "Empresa Importación";
    private static final String BRAND_NAME = "Marca Importación";
    private static final String CATEGORY_NAME = "Importación masiva";

    @Autowired ProductImportService importService;
    @Autowired ProductRepository productRepository;
    @Autowired ChangeLogRepository changeRepository;
    @Autowired StoredFileService storedFileService;
    @Autowired StorageService storageService;
    @Autowired StoredFileRepository storedFileRepository;
    @Autowired StoredFileRetentionService storedFileRetentionService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ensureProductImportMasters() {
        Instant now = Instant.now();
        if (count("empresas", COMPANY_ID) == 0) {
            jdbc.update("""
                    INSERT INTO empresas(id, nombre, nombre_normalizado, ruc, telefono, direccion, estado,
                        version, last_sequence, deleted, created_at, updated_at)
                    VALUES (?, ?, 'empresa importacion', '', '', '', TRUE, 0, 0, FALSE, ?, ?)
                    """, COMPANY_ID, COMPANY_NAME, Timestamp.from(now), Timestamp.from(now));
        }
        if (count("marcas", BRAND_ID) == 0) {
            jdbc.update("""
                    INSERT INTO marcas(id, empresa_id, nombre, nombre_normalizado, estado,
                        version, last_sequence, deleted, created_at, updated_at)
                    VALUES (?, ?, ?, 'marca importacion', TRUE, 0, 0, FALSE, ?, ?)
                    """, BRAND_ID, COMPANY_ID, BRAND_NAME, Timestamp.from(now), Timestamp.from(now));
        }
        if (count("categorias", CATEGORY_ID) == 0) {
            jdbc.update("""
                    INSERT INTO categorias(id, categoria_padre_id, nombre, nombre_normalizado, descripcion, estado,
                        version, last_sequence, deleted, created_at, updated_at)
                    VALUES (?, NULL, ?, 'importacion masiva', '', TRUE, 0, 0, FALSE, ?, ?)
                    """, CATEGORY_ID, CATEGORY_NAME, Timestamp.from(now), Timestamp.from(now));
        }
        if (count("marca_categorias", RELATION_ID) == 0) {
            jdbc.update("""
                    INSERT INTO marca_categorias(id, marca_id, categoria_id, estado,
                        version, last_sequence, deleted, created_at, updated_at)
                    VALUES (?, ?, ?, TRUE, 0, 0, FALSE, ?, ?)
                    """, RELATION_ID, BRAND_ID, CATEGORY_ID, Timestamp.from(now), Timestamp.from(now));
        }
    }

    @Test
    void previewDoesNotPublishAndConfirmationIsIdempotent() throws Exception {
        String code = "XLS-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] workbook = populatedTemplate(code, false);
        var file = excel(workbook);
        long productsBefore = productRepository.count();
        long changesBefore = changeRepository.count();

        var preview = importService.preview(file, "admin");
        assertThat(preview.errorRows()).isZero();
        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.warningRows()).isEqualTo(1);
        assertThat(productRepository.count()).isEqualTo(productsBefore);
        assertThat(changeRepository.count()).isEqualTo(changesBefore);

        var confirmed = importService.confirm(preview.importId());
        assertThat(confirmed.status()).isEqualTo(ProductImportStatus.CONFIRMED);
        assertThat(productRepository.count()).isEqualTo(productsBefore + 1);
        assertThat(changeRepository.count()).isEqualTo(changesBefore + 1);
        assertThat(importService.preview(file, "admin").importId()).isEqualTo(preview.importId());
        try (var report = WorkbookFactory.create(
                new ByteArrayInputStream(importService.report(preview.importId())))) {
            assertThat(report.getSheet("Resultado")).isNotNull();
        }
    }

    @Test
    void excelAndImageZipPublishACompleteActiveProduct() throws Exception {
        String code = "ZIP-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] workbook = populatedTemplate(code, true);
        byte[] archive = imageZip("productos/" + code + ".webp", "imagen-webp-ficticia".getBytes(StandardCharsets.UTF_8));
        var excel = excel(workbook);
        var images = new MockMultipartFile("images", "imagenes.zip", "application/zip", archive);
        long filesBefore = storedFileRepository.count();

        var preview = importService.preview(excel, images, "admin");
        assertThat(preview.errorRows()).isZero();
        assertThat(preview.warningRows()).isZero();

        var confirmed = importService.confirm(preview.importId());
        assertThat(confirmed.status()).isEqualTo(ProductImportStatus.CONFIRMED);
        assertThat(storedFileRepository.count()).isEqualTo(filesBefore + 1);
        var product = productRepository.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(product.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(product.getAggregateJson()).contains("files/").contains("\"primary\":true");
        assertThat(storedFileRepository.findAll()).anyMatch(file ->
                file.getOwnerId().equals(product.getId()) && file.getStatus() == StoredFileStatus.READY);
    }

    @Test
    void formulasAreRejectedBeforePreview() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(importService.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row row = row(workbook.getSheet("Productos"), 1);
            row.createCell(0).setCellValue("FORM-1");
            row.createCell(3).setCellFormula("CONCAT(\"Producto\",\" fórmula\")");
            workbook.write(output);
            var preview = importService.preview(excel(output.toByteArray()), "admin");
            assertThat(preview.errorRows()).isEqualTo(1);
            assertThat(preview.rows().get(0).messages()).anyMatch(message -> message.contains("fórmulas"));
        }
    }

    @Test
    void fileIntentChecksChecksumAndVisibility() throws Exception {
        byte[] bytes = "imagen-ficticia".getBytes(StandardCharsets.UTF_8);
        String checksum = Digests.sha256(bytes);
        var intent = storedFileService.createIntent(new FileIntentRequest(
                "foto.png", StoredFileType.PRODUCT_IMAGE, "image/png", bytes.length,
                checksum, FileVisibility.PUBLIC, "PRODUCT", UUID.randomUUID().toString()));
        var upload = new MockMultipartFile("file", "foto.png", "image/png", bytes);
        storedFileService.upload(intent.fileId(), upload);
        var ready = storedFileService.complete(intent.fileId());
        assertThat(ready.storageKey()).doesNotContain(":").doesNotContain("..");
        assertThat(storedFileService.download(intent.fileId(), true).resource().getInputStream().readAllBytes())
                .isEqualTo(bytes);

        var privateIntent = storedFileService.createIntent(new FileIntentRequest(
                "cotizacion.pdf", StoredFileType.DOCUMENT, "application/pdf", bytes.length,
                checksum, FileVisibility.PRIVATE, "QUOTE", UUID.randomUUID().toString()));
        storedFileService.upload(privateIntent.fileId(),
                new MockMultipartFile("file", "cotizacion.pdf", "application/pdf", bytes));
        storedFileService.complete(privateIntent.fileId());
        assertThatThrownBy(() -> storedFileService.download(privateIntent.fileId(), true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void incompleteUploadExpiresAndCannotBeCompleted() {
        byte[] bytes = "upload-incompleto".getBytes(StandardCharsets.UTF_8);
        var intent = storedFileService.createIntent(new FileIntentRequest(
                "foto.png", StoredFileType.PRODUCT_IMAGE, "image/png", bytes.length,
                Digests.sha256(bytes), FileVisibility.PRIVATE,
                "PRODUCT", UUID.randomUUID().toString()));
        storedFileService.upload(intent.fileId(),
                new MockMultipartFile("file", "foto.png", "image/png", bytes));
        var entity = storedFileRepository.findById(intent.fileId()).orElseThrow();
        entity.setExpiresAt(java.time.Instant.now().minusSeconds(1));
        storedFileRepository.saveAndFlush(entity);

        storedFileRetentionService.expireIncompleteUploads();

        assertThat(storedFileRepository.findById(intent.fileId()).orElseThrow().getStatus())
                .isEqualTo(StoredFileStatus.EXPIRED);
        assertThatThrownBy(() -> storedFileService.complete(intent.fileId()))
                .hasMessageContaining("expiró");
        assertThatThrownBy(() -> storageService.load(intent.storageKey()))
                .hasMessageContaining("no existe");
    }

    private byte[] populatedTemplate(String code, boolean activeWithImage) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(importService.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row product = row(workbook.getSheet("Productos"), 1);
            product.createCell(0).setCellValue(code);
            product.createCell(3).setCellValue("Producto importado");
            product.createCell(4).setCellValue("Vista previa segura");
            product.createCell(5).setCellValue(COMPANY_NAME);
            product.createCell(7).setCellValue(BRAND_NAME);
            product.createCell(9).setCellValue(CATEGORY_NAME);
            product.createCell(13).setCellValue("SINGLE");
            product.createCell(14).setCellValue(activeWithImage ? "ACTIVE" : "DRAFT");

            Row variant = row(workbook.getSheet("Variantes"), 1);
            variant.createCell(0).setCellValue(code);
            variant.createCell(1).setCellValue(code + "-SKU");
            variant.createCell(3).setCellValue("Variante principal");
            variant.createCell(4).setCellValue("ACTIVE");

            Row presentation = row(workbook.getSheet("Presentaciones"), 1);
            presentation.createCell(0).setCellValue(code);
            presentation.createCell(1).setCellValue(code + "-SKU");
            presentation.createCell(2).setCellValue("Unidad");
            presentation.createCell(3).setCellValue("UND");
            presentation.createCell(4).setCellValue(1);
            presentation.createCell(5).setCellValue(1);
            presentation.createCell(6).setCellValue(1);
            presentation.createCell(8).setCellValue("ACTIVE");

            if (activeWithImage) {
                Row price = row(workbook.getSheet("Precios"), 1);
                price.createCell(0).setCellValue(code);
                price.createCell(1).setCellValue(code + "-SKU");
                price.createCell(2).setCellValue("General");
                price.createCell(3).setCellValue("Unidad");
                price.createCell(4).setCellValue("PEN");
                price.createCell(5).setCellValue(18);
                price.createCell(6).setCellValue("precio_fijo");
                price.createCell(7).setCellValue(10.50);

                Row image = row(workbook.getSheet("Imagenes"), 1);
                image.createCell(0).setCellValue(code);
                image.createCell(2).setCellValue("productos/" + code + ".webp");
                image.createCell(3).setCellValue("PRODUCT");
                image.createCell(4).setCellValue("SI");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private int count(String table, String id) {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return result == null ? 0 : result;
    }

    private MockMultipartFile excel(byte[] bytes) {
        return new MockMultipartFile("file", "productos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private byte[] imageZip(String path, byte[] bytes) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(bytes);
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private Row row(Sheet sheet, int index) {
        Row row = sheet.getRow(index);
        return row == null ? sheet.createRow(index) : row;
    }
}
