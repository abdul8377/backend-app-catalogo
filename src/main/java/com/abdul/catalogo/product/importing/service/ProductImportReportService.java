package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.product.importing.entity.ProductImportEntity;
import com.abdul.catalogo.product.importing.entity.ProductImportRowEntity;
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

@Service
public class ProductImportReportService {
    public byte[] generate(ProductImportEntity item, List<ProductImportRowEntity> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Resultado");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("Importación"); title.createCell(1).setCellValue(item.getId());
            title.createCell(3).setCellValue("Estado"); title.createCell(4).setCellValue(item.getStatus().name());
            Row header = sheet.createRow(2);
            String[] headers = {"Fila", "CódigoFamilia", "Acción", "Estado", "ProductoId", "Versión", "Mensajes"};
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            for (int i = 0; i < headers.length; i++) { header.createCell(i).setCellValue(headers[i]); header.getCell(i).setCellStyle(headerStyle); }
            int index = 3;
            for (ProductImportRowEntity source : rows) {
                Row row = sheet.createRow(index++);
                row.createCell(0).setCellValue(source.getRowNumber()); row.createCell(1).setCellValue(source.getFamilyCode());
                row.createCell(2).setCellValue(source.getAction().name()); row.createCell(3).setCellValue(source.getStatus().name());
                row.createCell(4).setCellValue(source.getResultProductId() == null ? "" : source.getResultProductId());
                if (source.getResultVersion() != null) row.createCell(5).setCellValue(source.getResultVersion());
                row.createCell(6).setCellValue(source.getMessagesJson());
            }
            sheet.createFreezePane(0, 3); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2, Math.max(2, index - 1), 0, 6));
            int[] widths = {10, 20, 14, 14, 40, 12, 70}; for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            workbook.write(output); return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar el informe XLSX.", exception);
        }
    }
}
