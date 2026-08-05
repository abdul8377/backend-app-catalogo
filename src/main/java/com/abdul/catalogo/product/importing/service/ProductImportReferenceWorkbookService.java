package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.catalog.service.CatalogMasterDataService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProductImportReferenceWorkbookService {
    private final CatalogMasterDataService masterDataService;

    public ProductImportReferenceWorkbookService(CatalogMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    public byte[] generate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            CellStyle warning = warningStyle(workbook);

            var summary = masterDataService.summary();
            Sheet start = sheet(workbook, header, "Resumen", "Dato", "Cantidad / estado");
            add(start, "Empresas activas", summary.companies());
            add(start, "Marcas activas", summary.brands());
            add(start, "Categorías y subcategorías activas", summary.categories());
            add(start, "Relaciones marca–categoría", summary.brandCategoryRelations());
            add(start, "Unidades de medida", summary.measurementUnits());
            add(start, "Atributos de categoría", summary.categoryAttributes());
            Row readiness = start.createRow(start.getLastRowNum() + 1);
            readiness.createCell(0).setCellValue("Lista para importar productos");
            readiness.createCell(1).setCellValue(summary.readyForProductImport() ? "SÍ" : "NO");
            if (!summary.readyForProductImport()) readiness.getCell(1).setCellStyle(warning);
            Row note = start.createRow(start.getLastRowNum() + 2);
            note.createCell(0).setCellValue("Regla");
            note.createCell(1).setCellValue(
                    "Los productos solo pueden usar empresas, marcas y categorías que aparezcan en estas hojas.");

            fill(workbook, header, "Ref_Empresas",
                    List.of("EmpresaId", "Empresa", "RUC"), masterDataService.companiesForTemplate());
            fill(workbook, header, "Ref_Marcas",
                    List.of("MarcaId", "EmpresaId", "Empresa", "Marca"), masterDataService.brandsForTemplate());
            fill(workbook, header, "Ref_Categorias",
                    List.of("CategoriaId", "CategoriaPadreId", "CategoriaPadre", "Categoria", "Nivel"),
                    masterDataService.categoriesForTemplate());
            fill(workbook, header, "Ref_Unidades",
                    List.of("UnidadId", "Codigo", "Unidad", "Simbolo", "Magnitud"),
                    masterDataService.unitsForTemplate());
            fill(workbook, header, "Ref_Atributos",
                    List.of("AtributoId", "CategoriaId", "Categoria", "Atributo", "Clave", "TipoDato",
                            "NivelCaptura", "Requerido", "Filtrable", "PuedeSerEje"),
                    masterDataService.attributesForTemplate());

            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar el libro de datos maestros.", exception);
        }
    }

    private void fill(XSSFWorkbook workbook, CellStyle header, String name,
                      List<String> columns, List<Map<String, Object>> rows) {
        Sheet sheet = sheet(workbook, header, name, columns.toArray(String[]::new));
        int rowIndex = 1;
        for (Map<String, Object> source : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int column = 0; column < columns.size(); column++) {
                Object value = value(source, columns.get(column));
                if (value instanceof Number number) row.createCell(column).setCellValue(number.doubleValue());
                else if (value instanceof Boolean bool) row.createCell(column).setCellValue(bool ? "SI" : "NO");
                else row.createCell(column).setCellValue(value == null ? "" : value.toString());
            }
        }
    }

    private Object value(Map<String, Object> source, String key) {
        if (source.containsKey(key)) return source.get(key);
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        return source.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).replace("_", "").equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
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
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, 0, 0, Math.max(0, headers.length - 1)));
        return sheet;
    }

    private void add(Sheet sheet, String label, Object value) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        row.createCell(0).setCellValue(label);
        if (value instanceof Number number) row.createCell(1).setCellValue(number.doubleValue());
        else row.createCell(1).setCellValue(String.valueOf(value));
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GOLD.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle warningStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
