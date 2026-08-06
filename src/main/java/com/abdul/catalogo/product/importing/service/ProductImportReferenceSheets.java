package com.abdul.catalogo.product.importing.service;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductImportReferenceSheets {
    private final JdbcTemplate jdbc;

    public ProductImportReferenceSheets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(XSSFWorkbook workbook, CellStyle headerStyle) {
        reference(workbook, headerStyle, "Ref_Empresas",
                "SELECT id, nombre, ruc FROM empresas WHERE deleted = FALSE AND estado = TRUE ORDER BY nombre",
                "EmpresaId", "Empresa", "RUC");
        reference(workbook, headerStyle, "Ref_Marcas",
                "SELECT m.id, m.empresa_id, e.nombre, m.nombre FROM marcas m "
                        + "JOIN empresas e ON e.id = m.empresa_id "
                        + "WHERE m.deleted = FALSE AND m.estado = TRUE ORDER BY e.nombre, m.nombre",
                "MarcaId", "EmpresaId", "Empresa", "Marca");
        reference(workbook, headerStyle, "Ref_Categorias",
                "SELECT c.id, c.categoria_padre_id, p.nombre, c.nombre FROM categorias c "
                        + "LEFT JOIN categorias p ON p.id = c.categoria_padre_id "
                        + "WHERE c.deleted = FALSE AND c.estado = TRUE ORDER BY COALESCE(p.nombre, c.nombre), c.nombre",
                "CategoriaId", "CategoriaPadreId", "CategoriaPadre", "Categoria");
        reference(workbook, headerStyle, "Ref_MarcaCategorias",
                "SELECT mc.marca_id, m.nombre, mc.categoria_id, c.nombre FROM marca_categorias mc "
                        + "JOIN marcas m ON m.id = mc.marca_id JOIN categorias c ON c.id = mc.categoria_id "
                        + "WHERE mc.deleted = FALSE AND mc.estado = TRUE ORDER BY m.nombre, c.nombre",
                "MarcaId", "Marca", "CategoriaId", "CategoriaRelacionada");
        reference(workbook, headerStyle, "Ref_ListasPrecio",
                "SELECT id, nombre, moneda, incluye_igv, igv_porcentaje FROM listas_precios "
                        + "WHERE deleted = FALSE AND estado = TRUE ORDER BY nombre",
                "ListaPrecioId", "ListaPrecio", "Moneda", "IncluyeIGV", "IGV");
        reference(workbook, headerStyle, "Ref_Unidades",
                "SELECT id, codigo, nombre, simbolo, magnitud FROM unidades_medida "
                        + "WHERE deleted = FALSE AND estado = TRUE ORDER BY magnitud, codigo",
                "UnidadId", "Codigo", "Unidad", "Simbolo", "Magnitud");
    }

    private void reference(XSSFWorkbook workbook, CellStyle headerStyle, String name,
                           String sql, String... headers) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            var cell = header.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, Math.min(55, Math.max(16, headers[index].length() + 4)) * 256);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        int rowNumber = 1;
        for (Map<String, Object> source : rows) {
            Row row = sheet.createRow(rowNumber++);
            int column = 0;
            for (Object value : source.values()) {
                row.createCell(column++).setCellValue(value == null ? "" : value.toString());
            }
        }
        sheet.createFreezePane(0, 1);
        if (headers.length > 0) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.length - 1));
        }
    }
}
