package com.abdul.catalogo.product;

import com.abdul.catalogo.product.importing.model.ProductImportStatus;
import com.abdul.catalogo.product.importing.service.ProductImportService;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.storage.StoredFileService;
import com.abdul.catalogo.storage.StorageService;
import com.abdul.catalogo.storage.dto.FileIntentRequest;
import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileType;
import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.StoredFileRetentionService;
import com.abdul.catalogo.storage.repository.StoredFileRepository;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class ProductImportAndStorageIntegrationTest {
    @Autowired ProductImportService importService;
    @Autowired ProductRepository productRepository;
    @Autowired ChangeLogRepository changeRepository;
    @Autowired StoredFileService storedFileService;
    @Autowired StorageService storageService;
    @Autowired StoredFileRepository storedFileRepository;
    @Autowired StoredFileRetentionService storedFileRetentionService;

    @Test
    void previewDoesNotPublishAndConfirmationIsIdempotent() throws Exception {
        String code = "XLS-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] workbook = populatedTemplate(code);
        var file = new MockMultipartFile("file", "productos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        long productsBefore = productRepository.count(); long changesBefore = changeRepository.count();

        var preview = importService.preview(file, "admin");
        assertThat(preview.errorRows()).isZero(); assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(productRepository.count()).isEqualTo(productsBefore);
        assertThat(changeRepository.count()).isEqualTo(changesBefore);

        var confirmed = importService.confirm(preview.importId());
        assertThat(confirmed.status()).isEqualTo(ProductImportStatus.CONFIRMED);
        assertThat(productRepository.count()).isEqualTo(productsBefore + 1);
        assertThat(changeRepository.count()).isEqualTo(changesBefore + 1);
        assertThat(importService.preview(file, "admin").importId()).isEqualTo(preview.importId());
        try (var report = WorkbookFactory.create(new ByteArrayInputStream(importService.report(preview.importId())))) {
            assertThat(report.getSheet("Resultado")).isNotNull();
        }
    }

    @Test
    void formulasAreRejectedBeforePreview() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(importService.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row row = row(workbook.getSheet("Productos"), 1); row.createCell(0).setCellValue("FORM-1");
            row.createCell(3).setCellFormula("CONCAT(\"Producto\",\" fórmula\")"); workbook.write(output);
            var file = new MockMultipartFile("file", "formula.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
            var preview = importService.preview(file, "admin");
            assertThat(preview.errorRows()).isEqualTo(1);
            assertThat(preview.rows().get(0).messages()).anyMatch(message -> message.contains("fórmulas"));
        }
    }

    @Test
    void fileIntentChecksChecksumAndVisibility() throws Exception {
        byte[] bytes = "imagen-ficticia".getBytes(StandardCharsets.UTF_8); String checksum = Digests.sha256(bytes);
        var intent = storedFileService.createIntent(new FileIntentRequest("foto.png", StoredFileType.PRODUCT_IMAGE, "image/png", bytes.length,
                checksum, FileVisibility.PUBLIC, "PRODUCT", UUID.randomUUID().toString()));
        var upload = new MockMultipartFile("file", "foto.png", "image/png", bytes);
        storedFileService.upload(intent.fileId(), upload); var ready = storedFileService.complete(intent.fileId());
        assertThat(ready.storageKey()).doesNotContain(":").doesNotContain("..");
        assertThat(storedFileService.download(intent.fileId(), true).resource().getInputStream().readAllBytes()).isEqualTo(bytes);

        var privateIntent = storedFileService.createIntent(new FileIntentRequest("cotizacion.pdf", StoredFileType.DOCUMENT, "application/pdf", bytes.length,
                checksum, FileVisibility.PRIVATE, "QUOTE", UUID.randomUUID().toString()));
        storedFileService.upload(privateIntent.fileId(), new MockMultipartFile("file", "cotizacion.pdf", "application/pdf", bytes));
        storedFileService.complete(privateIntent.fileId());
        assertThatThrownBy(() -> storedFileService.download(privateIntent.fileId(), true)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void incompleteUploadExpiresAndCannotBeCompleted() {
        byte[] bytes = "upload-incompleto".getBytes(StandardCharsets.UTF_8);
        var intent = storedFileService.createIntent(new FileIntentRequest("foto.png", StoredFileType.PRODUCT_IMAGE,
                "image/png", bytes.length, Digests.sha256(bytes), FileVisibility.PRIVATE,
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

    private byte[] populatedTemplate(String code) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(importService.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row product = row(workbook.getSheet("Productos"), 1);
            product.createCell(0).setCellValue(code); product.createCell(3).setCellValue("Producto importado");
            product.createCell(4).setCellValue("Vista previa segura"); product.createCell(5).setCellValue("Empresa Demo");
            product.createCell(6).setCellValue("Marca Demo"); product.createCell(7).setCellValue("General");
            product.createCell(9).setCellValue("SINGLE"); product.createCell(10).setCellValue("ACTIVE");
            product.createCell(11).setCellValue("{}");
            Row variant = row(workbook.getSheet("Variantes"), 1);
            variant.createCell(0).setCellValue(code); variant.createCell(1).setCellValue(code + "-SKU");
            variant.createCell(3).setCellValue("Variante principal"); variant.createCell(4).setCellValue("{}");
            variant.createCell(5).setCellValue("ACTIVE"); workbook.write(output); return output.toByteArray();
        }
    }

    private Row row(Sheet sheet, int index) { Row row = sheet.getRow(index); return row == null ? sheet.createRow(index) : row; }
}
