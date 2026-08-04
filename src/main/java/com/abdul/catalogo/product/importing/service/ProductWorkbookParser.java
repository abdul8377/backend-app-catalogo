package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import com.abdul.catalogo.shared.config.ProductImportProperties;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ProductWorkbookParser {
    private final ProductImportProperties properties;
    private final ObjectMapper objectMapper;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public ProductWorkbookParser(ProductImportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<ProductImportCandidate> parse(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            rejectFormulas(workbook);
            List<RowData> products = rows(requireSheet(workbook, "Productos", "Products"));
            List<RowData> variants = rows(optionalSheet(workbook, "Variantes", "Variants"));
            List<RowData> presentations = rows(optionalSheet(workbook, "Presentaciones", "Presentations"));
            List<RowData> prices = rows(optionalSheet(workbook, "Precios", "Prices"));
            List<RowData> images = rows(optionalSheet(workbook, "Imagenes", "Images"));
            int total = products.size() + variants.size() + presentations.size() + prices.size() + images.size();
            if (total > properties.maxRows()) {
                throw new BusinessRuleException("IMPORT_ROW_LIMIT", "El libro supera el máximo de " + properties.maxRows() + " filas.");
            }
            return candidates(products, variants, presentations, prices, images);
        } catch (IOException exception) {
            throw new BusinessRuleException("INVALID_XLSX", "No se pudo abrir el archivo XLSX.");
        }
    }

    private List<ProductImportCandidate> candidates(List<RowData> products, List<RowData> variants,
                                                    List<RowData> presentations, List<RowData> prices,
                                                    List<RowData> images) {
        Map<String, RowData> productByFamily = new LinkedHashMap<>();
        Set<String> allFamilies = new LinkedHashSet<>();
        products.forEach(row -> {
            String family = row.value("codigofamilia").trim();
            if (!family.isBlank()) {
                allFamilies.add(family);
                productByFamily.putIfAbsent(family, row);
            }
        });
        List.of(variants, presentations, prices, images).forEach(rows -> rows.forEach(row -> {
            String family = row.value("codigofamilia").trim();
            if (!family.isBlank()) allFamilies.add(family);
        }));

        List<ProductImportCandidate> result = new ArrayList<>();
        for (String family : allFamilies) {
            RowData product = productByFamily.get(family);
            List<String> warnings = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            ObjectNode aggregate = objectMapper.createObjectNode();
            if (product == null) {
                errors.add("No existe una fila en Productos para la familia " + family + '.');
                product = new RowData(0, Map.of("codigofamilia", family));
            }
            put(aggregate, "code", family);
            put(aggregate, "name", product.value("nombre"));
            put(aggregate, "description", product.value("descripcion"));
            put(aggregate, "company", product.value("empresa"));
            put(aggregate, "companyId", "");
            put(aggregate, "brand", product.value("marca"));
            put(aggregate, "brandId", "");
            put(aggregate, "category", product.value("categoria"));
            put(aggregate, "categoryId", "");
            put(aggregate, "subcategory", product.value("subcategoria"));
            put(aggregate, "subcategoryId", "");
            put(aggregate, "productType", enumValue(product.value("tipo"), ProductType.SINGLE.name(), ProductType.class, errors));
            put(aggregate, "status", enumValue(product.value("estado"), ProductStatus.ACTIVE.name(), ProductStatus.class, errors));
            aggregate.set("attributes", json(product.value("atributosjson"), true, "AtributosJson", errors));
            aggregate.set("variants", variantArray(family, variants, errors));
            aggregate.set("presentations", presentationArray(family, presentations, errors));
            aggregate.set("prices", priceArray(family, prices, errors));
            aggregate.set("images", imageArray(family, images, warnings));
            if (aggregate.path("name").asText().isBlank()) errors.add("Nombre es obligatorio.");
            if (aggregate.path("variants").isEmpty()) errors.add("Debe existir al menos una variante.");
            if (aggregate.path("images").isEmpty()) warnings.add("El producto no tiene imágenes declaradas.");
            String productId = product.value("productoid").trim();
            Long version = longValue(product.value("version"), "Version", errors);
            result.add(new ProductImportCandidate(product.rowNumber(), family, blankToNull(productId), version,
                    aggregate, List.copyOf(warnings), List.copyOf(errors)));
        }
        if (result.isEmpty()) {
            throw new BusinessRuleException("EMPTY_IMPORT", "La hoja Productos no contiene filas para importar.");
        }
        return result;
    }

    private ArrayNode variantArray(String family, List<RowData> rows, List<String> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            ObjectNode node = array.addObject();
            put(node, "sku", row.value("sku")); put(node, "supplierCode", row.value("codigoproveedor"));
            put(node, "shortName", row.value("nombrecorto")); put(node, "status", defaultValue(row.value("estado"), "ACTIVE"));
            node.set("attributes", json(row.value("atributosjson"), true, "Variantes.AtributosJson", errors));
            if (node.path("sku").asText().isBlank()) errors.add("Variantes fila " + row.rowNumber() + ": SKU es obligatorio.");
        }
        return array;
    }

    private ArrayNode presentationArray(String family, List<RowData> rows, List<String> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            ObjectNode node = array.addObject(); put(node, "sku", row.value("sku"));
            put(node, "name", row.value("presentacion")); put(node, "baseUnit", row.value("unidadbase"));
            decimal(node, "equivalence", row.value("equivalencia"), errors, "Presentaciones.Equivalencia");
            decimal(node, "minimumSale", row.value("ventaminima"), errors, "Presentaciones.VentaMinima");
            put(node, "status", defaultValue(row.value("estado"), "ACTIVE"));
        }
        return array;
    }

    private ArrayNode priceArray(String family, List<RowData> rows, List<String> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            ObjectNode node = array.addObject(); put(node, "sku", row.value("sku"));
            put(node, "priceList", row.value("listaprecio")); put(node, "presentation", row.value("presentacion"));
            put(node, "currency", defaultValue(row.value("moneda"), "PEN"));
            decimal(node, "taxRate", row.value("igv"), errors, "Precios.IGV");
            decimal(node, "price", row.value("precio"), errors, "Precios.Precio");
            node.put("quoteRequired", booleanValue(row.value("cotizar")));
            put(node, "validity", row.value("vigencia"));
        }
        return array;
    }

    private ArrayNode imageArray(String family, List<RowData> rows, List<String> warnings) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            ObjectNode node = array.addObject(); put(node, "sku", row.value("sku"));
            String file = row.value("archivo");
            if (file.contains("..") || file.startsWith("/") || file.matches("^[A-Za-z]:.*")) {
                warnings.add("Imágenes fila " + row.rowNumber() + ": se omitió una ruta no relativa.");
                array.remove(array.size() - 1); continue;
            }
            put(node, "storageKey", file); put(node, "type", defaultValue(row.value("tipo"), "PRODUCT"));
            node.put("primary", booleanValue(row.value("principal")));
        }
        return array;
    }

    private List<RowData> rows(Sheet sheet) {
        if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) return List.of();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) return List.of();
        Map<Integer, String> columns = new LinkedHashMap<>();
        for (Cell cell : header) columns.put(cell.getColumnIndex(), normalize(formatter.formatCellValue(cell)));
        List<RowData> result = new ArrayList<>();
        for (int index = header.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index); if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>(); boolean any = false;
            for (var column : columns.entrySet()) {
                String value = formatter.formatCellValue(row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
                values.put(column.getValue(), value); any |= !value.isBlank();
            }
            if (any) result.add(new RowData(index + 1, values));
        }
        return result;
    }

    private void rejectFormulas(Workbook workbook) {
        for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA) {
                throw new BusinessRuleException("FORMULAS_NOT_ALLOWED", "No se permiten fórmulas (" + sheet.getSheetName() + "!" + cell.getAddress() + ").");
            }
        }
    }

    private Sheet requireSheet(Workbook workbook, String... names) {
        Sheet sheet = optionalSheet(workbook, names);
        if (sheet == null) throw new BusinessRuleException("MISSING_PRODUCTS_SHEET", "Falta la hoja Productos.");
        return sheet;
    }

    private Sheet optionalSheet(Workbook workbook, String... names) {
        for (String name : names) { Sheet sheet = workbook.getSheet(name); if (sheet != null) return sheet; }
        return null;
    }

    private List<RowData> familyRows(String family, List<RowData> rows) {
        return rows.stream().filter(row -> family.equalsIgnoreCase(row.value("codigofamilia"))).toList();
    }

    private JsonNode json(String value, boolean object, String field, List<String> errors) {
        try {
            JsonNode node = objectMapper.readTree(value == null || value.isBlank() ? (object ? "{}" : "[]") : value);
            if (node == null || object != node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (JacksonException | IllegalArgumentException exception) {
            errors.add(field + " contiene JSON inválido.");
            return object ? objectMapper.createObjectNode() : objectMapper.createArrayNode();
        }
    }

    private <E extends Enum<E>> String enumValue(String value, String fallback, Class<E> type, List<String> errors) {
        String normalized = defaultValue(value, fallback).toUpperCase();
        try { Enum.valueOf(type, normalized); return normalized; }
        catch (IllegalArgumentException exception) { errors.add("Valor no reconocido: " + value); return fallback; }
    }

    private Long longValue(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value.replace(".0", "")); }
        catch (NumberFormatException exception) { errors.add(field + " debe ser un entero."); return null; }
    }

    private void decimal(ObjectNode node, String field, String value, List<String> errors, String label) {
        if (value == null || value.isBlank()) return;
        try { node.put(field, new BigDecimal(value.replace(',', '.'))); }
        catch (NumberFormatException exception) { errors.add(label + " debe ser numérico."); }
    }

    private boolean booleanValue(String value) {
        return Set.of("si", "sí", "true", "1", "x").contains(value == null ? "" : value.trim().toLowerCase());
    }

    private void put(ObjectNode node, String field, String value) { node.put(field, value == null ? "" : value.trim()); }
    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(); }
    private record RowData(int rowNumber, Map<String, String> values) { String value(String key) { return values.getOrDefault(key, ""); } }
}
