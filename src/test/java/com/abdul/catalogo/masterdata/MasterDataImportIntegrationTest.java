package com.abdul.catalogo.masterdata;

import com.abdul.catalogo.masterdata.service.MasterDataImportService;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import com.abdul.catalogo.synchronization.service.SyncReadService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class MasterDataImportIntegrationTest {
    @Autowired MasterDataImportService importService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChangeLogRepository changes;
    @Autowired SyncReadService syncReadService;

    @Test
    void excelCreatesRelationalMastersAndPublishesThemForTabletBootstrap() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String company = "Empresa Maestra " + suffix;
        String brand = "Marca Maestra " + suffix;
        String rootCategory = "Línea Maestra " + suffix;
        String childCategory = "Pernos Maestros " + suffix;
        String unitCode = "MM" + suffix.substring(0, 3).toUpperCase();
        String attribute = "Diámetro " + suffix;
        String priceList = "Lista Maestra " + suffix;
        byte[] workbook = workbook(company, brand, rootCategory, childCategory, unitCode, attribute, priceList);
        var file = new MockMultipartFile("file", "maestros.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        long changesBefore = changes.count();

        var preview = importService.preview(file, "admin-test");

        assertThat(preview.errorRows()).isZero();
        assertThat(preview.totalRows()).isEqualTo(8);
        assertThat(preview.status()).isEqualTo("PREVIEW_READY");
        assertThat(count("empresas", "nombre", company)).isZero();

        var confirmed = importService.confirm(preview.id());

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(count("empresas", "nombre", company)).isEqualTo(1);
        assertThat(count("marcas", "nombre", brand)).isEqualTo(1);
        assertThat(count("categorias", "nombre", rootCategory)).isEqualTo(1);
        assertThat(count("categorias", "nombre", childCategory)).isEqualTo(1);
        assertThat(count("unidades_medida", "codigo", unitCode)).isEqualTo(1);
        assertThat(count("categoria_atributos", "nombre", attribute)).isEqualTo(1);
        assertThat(count("listas_precios", "nombre", priceList)).isEqualTo(1);
        assertThat(changes.count()).isEqualTo(changesBefore + 8);
        assertThat(jdbc.queryForList("""
                SELECT payload_json FROM sync_records
                WHERE entity_type IN ('COMPANY','BRAND','CATEGORY','BRAND_CATEGORY',
                    'MEASUREMENT_UNIT','CATEGORY_ATTRIBUTE','PRICE_LIST')
                  AND payload_json <> '{}'
                """)).isEmpty();

        var bootstrap = syncReadService.bootstrap(0, 300, null);
        assertThat(bootstrap.records()).anyMatch(record ->
                record.entityType().equals("COMPANY")
                        && record.payload().path("nombre").asText().equals(company));
        assertThat(bootstrap.records()).anyMatch(record ->
                record.entityType().equals("PRICE_LIST")
                        && record.payload().path("nombre").asText().equals(priceList)
                        && record.payload().path("moneda").asText().equals("PEN"));

        long changesAfterConfirmation = changes.count();
        var repeatedPreview = importService.preview(file, "admin-test");
        var repeatedConfirmation = importService.confirm(repeatedPreview.id());
        assertThat(repeatedPreview.id()).isEqualTo(preview.id());
        assertThat(repeatedConfirmation.status()).isEqualTo("CONFIRMED");
        assertThat(changes.count()).isEqualTo(changesAfterConfirmation);
    }

    private byte[] workbook(String company, String brand, String rootCategory, String childCategory,
                            String unitCode, String attribute, String priceList) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(importService.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row companyRow = row(workbook.getSheet("Empresas"), 1);
            companyRow.createCell(1).setCellValue(company);
            companyRow.createCell(6).setCellValue("SI");

            Row brandRow = row(workbook.getSheet("Marcas"), 1);
            brandRow.createCell(2).setCellValue(company);
            brandRow.createCell(3).setCellValue(brand);
            brandRow.createCell(4).setCellValue("SI");

            Row root = row(workbook.getSheet("Categorias"), 1);
            root.createCell(2).setCellValue(rootCategory);
            root.createCell(4).setCellValue("SI");
            Row child = row(workbook.getSheet("Categorias"), 2);
            child.createCell(1).setCellValue(rootCategory);
            child.createCell(2).setCellValue(childCategory);
            child.createCell(4).setCellValue("SI");

            Row relation = row(workbook.getSheet("MarcaCategorias"), 1);
            relation.createCell(1).setCellValue(company);
            relation.createCell(3).setCellValue(brand);
            relation.createCell(5).setCellValue(rootCategory);
            relation.createCell(6).setCellValue("SI");

            Row unit = row(workbook.getSheet("Unidades"), 1);
            unit.createCell(1).setCellValue(unitCode);
            unit.createCell(2).setCellValue("Milímetro maestro");
            unit.createCell(3).setCellValue("mm");
            unit.createCell(4).setCellValue("longitud");
            unit.createCell(5).setCellValue(1);
            unit.createCell(6).setCellValue(2);
            unit.createCell(7).setCellValue("SI");

            Row attributeRow = row(workbook.getSheet("Atributos"), 1);
            attributeRow.createCell(2).setCellValue(rootCategory + " > " + childCategory);
            attributeRow.createCell(3).setCellValue(attribute);
            attributeRow.createCell(4).setCellValue("diametro_" + unitCode.toLowerCase());
            attributeRow.createCell(5).setCellValue("numero_unidad");
            attributeRow.createCell(6).setCellValue("variante");
            attributeRow.createCell(7).setCellValue("SI");
            attributeRow.createCell(8).setCellValue("SI");
            attributeRow.createCell(9).setCellValue("SI");
            attributeRow.createCell(10).setCellValue("SI");
            attributeRow.createCell(11).setCellValue(1);
            attributeRow.createCell(12).setCellValue("SI");

            Row price = row(workbook.getSheet("ListasPrecio"), 1);
            price.createCell(1).setCellValue(priceList);
            price.createCell(2).setCellValue("PEN");
            price.createCell(3).setCellValue("SI");
            price.createCell(4).setCellValue(18);
            price.createCell(5).setCellValue("SI");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private int count(String table, String column, String value) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
        return count == null ? 0 : count;
    }

    private Row row(Sheet sheet, int index) {
        Row row = sheet.getRow(index);
        return row == null ? sheet.createRow(index) : row;
    }
}
