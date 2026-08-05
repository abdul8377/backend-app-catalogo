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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        return parse(bytes, Set.of());
    }

    public List<ProductImportCandidate> parse(byte[] bytes, Set<String> imageEntries) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            rejectFormulas(workbook);
            List<RowData> products = rows(requireSheet(workbook, "Productos", "Products"));
            List<RowData> variants = rows(optionalSheet(workbook, "Variantes", "Variants"));
            List<RowData> attributes = rows(optionalSheet(workbook, "Atributos", "Attributes"));
            List<RowData> presentations = rows(optionalSheet(workbook, "Presentaciones", "Presentations"));
            List<RowData> prices = rows(optionalSheet(workbook, "Precios", "Prices"));
            List<RowData> images = rows(optionalSheet(workbook, "Imagenes", "Images"));
            List<RowData> sources = rows(optionalSheet(workbook, "Fuentes", "Sources"));
            int total = products.size() + variants.size() + attributes.size() + presentations.size()
                    + prices.size() + images.size() + sources.size();
            if (total > properties.maxRows()) {
                throw new BusinessRuleException("IMPORT_ROW_LIMIT",
                        "El libro supera el máximo de " + properties.maxRows() + " filas.");
            }
            return candidates(products, variants, attributes, presentations, prices, images, imageEntries);
        } catch (IOException exception) {
            throw new BusinessRuleException("INVALID_XLSX", "No se pudo abrir el archivo XLSX.");
        }
    }

    private List<ProductImportCandidate> candidates(List<RowData> products, List<RowData> variants,
                                                    List<RowData> attributes, List<RowData> presentations,
                                                    List<RowData> prices, List<RowData> images,
                                                    Set<String> imageEntries) {
        Map<String, RowData> productByFamily = new LinkedHashMap<>();
        Set<String> allFamilies = new LinkedHashSet<>();
        products.forEach(row -> {
            String family = row.value("codigofamilia").trim();
            if (!family.isBlank()) {
                allFamilies.add(family);
                productByFamily.putIfAbsent(family.toUpperCase(Locale.ROOT), row);
            }
        });
        List.of(variants, attributes, presentations, prices, images).forEach(childRows -> childRows.forEach(row -> {
            String family = row.value("codigofamilia").trim();
            if (!family.isBlank()) allFamilies.add(family);
        }));

        List<ProductImportCandidate> result = new ArrayList<>();
        for (String familyRaw : allFamilies) {
            String family = familyRaw.trim().toUpperCase(Locale.ROOT);
            RowData product = productByFamily.get(family);
            List<String> warnings = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            if (product == null) {
                errors.add("No existe una fila en Productos para la familia " + family + '.');
                product = new RowData(0, Map.of("codigofamilia", family));
            }

            ObjectNode aggregate = objectMapper.createObjectNode();
            put(aggregate, "code", family);
            put(aggregate, "name", product.value("nombre"));
            put(aggregate, "description", product.value("descripcion"));
            put(aggregate, "company", product.value("empresa"));
            put(aggregate, "companyId", product.value("empresaid"));
            put(aggregate, "brand", product.value("marca"));
            put(aggregate, "brandId", product.value("marcaid"));
            put(aggregate, "category", product.value("categoria"));
            put(aggregate, "categoryId", product.value("categoriaid"));
            put(aggregate, "subcategory", product.value("subcategoria"));
            put(aggregate, "subcategoryId", product.value("subcategoriaid"));
            put(aggregate, "productType", enumValue(product.value("tipo"), ProductType.SINGLE.name(), ProductType.class, errors));
            put(aggregate, "status", enumValue(product.value("estado"), ProductStatus.DRAFT.name(), ProductStatus.class, errors));

            Map<String, ObjectNode> variantBySku = new LinkedHashMap<>();
            ArrayNode variantArray = variantArray(family, variants, attributes, errors, variantBySku);
            ObjectNode familyAttributes = familyAttributes(family, attributes, warnings);
            ArrayNode presentationArray = presentationArray(family, presentations, variantBySku.keySet(), errors);
            ArrayNode priceArray = priceArray(family, prices, variantBySku, presentationArray, errors);
            ArrayNode imageArray = imageArray(family, images, imageEntries, warnings, errors);

            aggregate.set("attributes", familyAttributes);
            aggregate.set("variants", variantArray);
            aggregate.set("presentations", presentationArray);
            aggregate.set("prices", priceArray);
            aggregate.set("images", imageArray);
            aggregate.set("familyAxes", objectMapper.createArrayNode());
            aggregate.set("attributeValues", objectMapper.createArrayNode());
            aggregate.set("attributeOptions", objectMapper.createArrayNode());
            aggregate.set("salesConfiguration", salesConfiguration(presentationArray, variantBySku));
            aggregate.set("pricingConfiguration", pricingConfiguration(priceArray, presentationArray, variantBySku));
            ObjectNode imageConfiguration = objectMapper.createObjectNode();
            imageConfiguration.set("remote_images", imageArray.deepCopy());
            aggregate.set("imageConfiguration", imageConfiguration);

            if (aggregate.path("name").asText().isBlank()) errors.add("Nombre es obligatorio.");
            if (aggregate.path("company").asText().isBlank()) errors.add("Empresa es obligatoria.");
            if (aggregate.path("brand").asText().isBlank()) errors.add("Marca es obligatoria.");
            if (aggregate.path("category").asText().isBlank()) errors.add("Categoría es obligatoria.");
            if (variantArray.isEmpty()) errors.add("Debe existir al menos una variante.");
            if (presentationArray.isEmpty()) errors.add("Debe existir al menos una presentación.");
            if (imageArray.isEmpty()) warnings.add("El producto no tiene imágenes declaradas.");

            String productId = blankToNull(product.value("productoid").trim());
            Long version = longValue(product.value("version"), "Version", errors);
            result.add(new ProductImportCandidate(product.rowNumber(), family, productId, version,
                    aggregate, List.copyOf(warnings), List.copyOf(errors)));
        }
        if (result.isEmpty()) {
            throw new BusinessRuleException("EMPTY_IMPORT", "La hoja Productos no contiene filas para importar.");
        }
        return result;
    }

    private ObjectNode familyAttributes(String family, List<RowData> rows, List<String> warnings) {
        ObjectNode result = objectMapper.createObjectNode();
        for (RowData row : familyRows(family, rows)) {
            if (!row.value("sku").isBlank()) continue;
            String name = row.value("atributo").trim();
            if (name.isBlank()) {
                warnings.add("Atributos fila " + row.rowNumber() + ": se omitió un atributo sin nombre.");
                continue;
            }
            ObjectNode value = result.putObject(name);
            value.put("value", row.value("valor"));
            value.put("unit", row.value("unidad"));
        }
        return result;
    }

    private ArrayNode variantArray(String family, List<RowData> rows, List<RowData> attributes,
                                   List<String> errors, Map<String, ObjectNode> variantBySku) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            ObjectNode node = array.addObject();
            String sku = row.value("sku").trim().toUpperCase(Locale.ROOT);
            put(node, "id", stableId("variant", family, sku));
            put(node, "sku", sku);
            put(node, "supplierCode", row.value("codigoproveedor"));
            put(node, "shortName", row.value("nombrecorto"));
            put(node, "status", defaultValue(row.value("estado"), "ACTIVE").toUpperCase(Locale.ROOT));
            ObjectNode variantAttributes = objectMapper.createObjectNode();
            for (RowData attribute : familyRows(family, attributes)) {
                if (!sku.equalsIgnoreCase(attribute.value("sku").trim())) continue;
                String name = attribute.value("atributo").trim();
                if (name.isBlank()) continue;
                ObjectNode value = variantAttributes.putObject(name);
                value.put("value", attribute.value("valor"));
                value.put("unit", attribute.value("unidad"));
            }
            node.set("attributes", variantAttributes);
            if (sku.isBlank()) errors.add("Variantes fila " + row.rowNumber() + ": SKU es obligatorio.");
            if (node.path("shortName").asText().isBlank()) errors.add("Variantes fila " + row.rowNumber() + ": NombreCorto es obligatorio.");
            if (!sku.isBlank() && variantBySku.putIfAbsent(sku, node) != null) {
                errors.add("Variantes fila " + row.rowNumber() + ": SKU " + sku + " está repetido.");
            }
        }
        return array;
    }

    private ArrayNode presentationArray(String family, List<RowData> rows, Set<String> skus, List<String> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            String sku = row.value("sku").trim().toUpperCase(Locale.ROOT);
            String name = row.value("presentacion").trim();
            if (!sku.isBlank() && !skus.contains(sku)) {
                errors.add("Presentaciones fila " + row.rowNumber() + ": SKU " + sku + " no existe.");
            }
            ObjectNode node = array.addObject();
            put(node, "id", stableId("presentation", family, sku, name));
            put(node, "sku", sku);
            put(node, "name", name);
            put(node, "baseUnit", defaultValue(row.value("unidadbase"), "UND").toUpperCase(Locale.ROOT));
            decimal(node, "equivalence", defaultValue(row.value("equivalencia"), "1"), errors,
                    "Presentaciones fila " + row.rowNumber() + " Equivalencia");
            decimal(node, "minimumSale", defaultValue(row.value("ventaminima"), "1"), errors,
                    "Presentaciones fila " + row.rowNumber() + " VentaMinima");
            decimal(node, "purchaseIncrement", defaultValue(row.value("incremento"), "1"), errors,
                    "Presentaciones fila " + row.rowNumber() + " Incremento");
            node.put("allowsDecimals", booleanValue(row.value("permitedecimales")));
            put(node, "status", defaultValue(row.value("estado"), "ACTIVE").toUpperCase(Locale.ROOT));
            if (name.isBlank()) errors.add("Presentaciones fila " + row.rowNumber() + ": Presentacion es obligatoria.");
        }
        return array;
    }

    private ArrayNode priceArray(String family, List<RowData> rows, Map<String, ObjectNode> variantBySku,
                                 ArrayNode presentations, List<String> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        for (RowData row : familyRows(family, rows)) {
            String sku = row.value("sku").trim().toUpperCase(Locale.ROOT);
            String presentation = row.value("presentacion").trim();
            if (!variantBySku.containsKey(sku)) {
                errors.add("Precios fila " + row.rowNumber() + ": SKU " + sku + " no existe.");
            }
            ObjectNode presentationNode = findPresentation(presentations, sku, presentation);
            if (presentationNode == null) {
                errors.add("Precios fila " + row.rowNumber() + ": presentación " + presentation + " no corresponde al SKU " + sku + '.');
            }
            ObjectNode node = array.addObject();
            put(node, "sku", sku);
            put(node, "variantId", variantBySku.containsKey(sku) ? variantBySku.get(sku).path("id").asText() : "");
            put(node, "priceList", defaultValue(row.value("listaprecio"), "General"));
            put(node, "presentation", presentation);
            put(node, "presentationId", presentationNode == null ? "" : presentationNode.path("id").asText());
            put(node, "currency", defaultValue(row.value("moneda"), "PEN").toUpperCase(Locale.ROOT));
            decimal(node, "taxRate", defaultValue(row.value("igv"), "18"), errors,
                    "Precios fila " + row.rowNumber() + " IGV");
            boolean quote = booleanValue(row.value("cotizar"))
                    || row.value("configuracion").equalsIgnoreCase("por_cotizar")
                    || row.value("configuracion").equalsIgnoreCase("quote");
            node.put("quoteRequired", quote);
            put(node, "configuration", quote ? "por_cotizar" : "precio_fijo");
            if (!quote) decimal(node, "price", row.value("precio"), errors,
                    "Precios fila " + row.rowNumber() + " Precio");
            else node.putNull("price");
        }
        return array;
    }

    private ArrayNode imageArray(String family, List<RowData> rows, Set<String> imageEntries,
                                 List<String> warnings, List<String> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        boolean primary = false;
        for (RowData row : familyRows(family, rows)) {
            String file = safeRelative(row.value("archivo"), "Imágenes fila " + row.rowNumber(), errors);
            if (file.isBlank()) continue;
            boolean exists = imageEntries.stream().anyMatch(entry -> normalizePath(entry).equals(normalizePath(file)));
            if (imageEntries.isEmpty()) {
                errors.add("Imágenes fila " + row.rowNumber() + ": adjunta un ZIP que contenga " + file + '.');
            } else if (!exists) {
                errors.add("Imágenes fila " + row.rowNumber() + ": " + file + " no existe dentro del ZIP.");
            }
            ObjectNode node = array.addObject();
            put(node, "sku", row.value("sku").trim().toUpperCase(Locale.ROOT));
            put(node, "sourceFile", file);
            put(node, "type", defaultValue(row.value("tipo"), "PRODUCT"));
            boolean requestedPrimary = booleanValue(row.value("principal"));
            node.put("primary", requestedPrimary && !primary);
            if (requestedPrimary && primary) warnings.add("Solo se conservará la primera imagen principal de " + family + '.');
            primary |= requestedPrimary;
        }
        if (!primary && !array.isEmpty()) array.get(0).deepCopy();
        if (!primary && !array.isEmpty() && array.get(0) instanceof ObjectNode first) first.put("primary", true);
        return array;
    }

    private ObjectNode salesConfiguration(ArrayNode presentations, Map<String, ObjectNode> variants) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode rows = result.putArray("presentations");
        for (JsonNode presentation : presentations) {
            ObjectNode row = rows.addObject();
            row.put("id", presentation.path("id").asText());
            row.put("name", presentation.path("name").asText());
            row.put("base_unit", presentation.path("baseUnit").asText("UND"));
            row.set("equivalent_to", presentation.path("equivalence"));
            row.set("minimum_order", presentation.path("minimumSale"));
            row.set("purchase_increment", presentation.path("purchaseIncrement"));
            row.put("allows_decimals", presentation.path("allowsDecimals").asBoolean(false));
            ArrayNode assigned = row.putArray("assigned_variant_ids");
            String sku = presentation.path("sku").asText();
            if (sku.isBlank()) variants.values().forEach(variant -> assigned.add(variant.path("id").asText()));
            else if (variants.containsKey(sku)) assigned.add(variants.get(sku).path("id").asText());
            row.putArray("default_variant_ids");
            row.putArray("variant_rules");
        }
        result.put("uses_logistics_packages", false);
        result.putArray("logistics_packages");
        result.put("has_product_content", false);
        result.putArray("content_items");
        return result;
    }

    private ObjectNode pricingConfiguration(ArrayNode prices, ArrayNode presentations,
                                            Map<String, ObjectNode> variants) {
        ObjectNode result = objectMapper.createObjectNode();
        Map<String, String> listIds = new LinkedHashMap<>();
        for (JsonNode price : prices) {
            String name = price.path("priceList").asText("General");
            listIds.putIfAbsent(name, stableId("price-list", name));
        }
        ArrayNode lists = result.putArray("lists");
        listIds.forEach((name, id) -> {
            ObjectNode list = lists.addObject();
            list.put("id", id);
            list.put("name", name);
            list.put("currency_code", firstCurrency(prices, name));
            list.put("includes_igv", true);
            list.put("valid_from", java.time.LocalDate.now().toString());
            list.putNull("valid_until");
        });
        ArrayNode configured = result.putArray("prices");
        for (JsonNode price : prices) {
            ObjectNode row = configured.addObject();
            row.put("list_id", listIds.get(price.path("priceList").asText("General")));
            row.put("variant_id", price.path("variantId").asText());
            row.put("presentation_id", price.path("presentationId").asText());
            row.put("configuration", price.path("quoteRequired").asBoolean(false) ? "quote" : "fixed");
            if (price.path("quoteRequired").asBoolean(false)) row.putNull("fixed_price");
            else row.set("fixed_price", price.path("price"));
            row.putArray("ranges");
        }
        ArrayNode combinations = result.putArray("sellable_combinations");
        for (JsonNode presentation : presentations) {
            String sku = presentation.path("sku").asText();
            List<ObjectNode> assigned = sku.isBlank() ? new ArrayList<>(variants.values())
                    : variants.containsKey(sku) ? List.of(variants.get(sku)) : List.of();
            for (ObjectNode variant : assigned) {
                ObjectNode row = combinations.addObject();
                row.put("variant_id", variant.path("id").asText());
                row.put("variant_label", variant.path("shortName").asText());
                row.put("presentation_id", presentation.path("id").asText());
                row.put("presentation_label", presentation.path("name").asText());
                row.put("base_unit", presentation.path("baseUnit").asText("UND"));
                row.set("equivalent_to_base_unit", presentation.path("equivalence"));
                row.set("minimum_order", presentation.path("minimumSale"));
                row.set("purchase_increment", presentation.path("purchaseIncrement"));
            }
        }
        return result;
    }

    private String firstCurrency(ArrayNode prices, String listName) {
        for (JsonNode price : prices) if (price.path("priceList").asText().equals(listName)) return price.path("currency").asText("PEN");
        return "PEN";
    }

    private ObjectNode findPresentation(ArrayNode presentations, String sku, String name) {
        for (JsonNode raw : presentations) {
            if (!(raw instanceof ObjectNode item)) continue;
            String itemSku = item.path("sku").asText();
            if ((itemSku.isBlank() || itemSku.equalsIgnoreCase(sku))
                    && item.path("name").asText().equalsIgnoreCase(name)) return item;
        }
        return null;
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
                Cell cell = row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                values.put(column.getValue(), value); any |= !value.isBlank();
            }
            if (any) result.add(new RowData(index + 1, values));
        }
        return result;
    }

    private void rejectFormulas(Workbook workbook) {
        for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA) {
                throw new BusinessRuleException("FORMULAS_NOT_ALLOWED",
                        "No se permiten fórmulas (" + sheet.getSheetName() + "!" + cell.getAddress() + ").");
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
        return rows.stream().filter(row -> family.equalsIgnoreCase(row.value("codigofamilia").trim())).toList();
    }

    private <E extends Enum<E>> String enumValue(String value, String fallback, Class<E> type, List<String> errors) {
        String normalized = defaultValue(value, fallback).toUpperCase(Locale.ROOT);
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
        return Set.of("si", "sí", "true", "1", "x").contains(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
    }

    private String safeRelative(String value, String label, List<String> errors) {
        String file = value == null ? "" : value.replace('\\', '/').trim();
        if (file.isBlank()) {
            errors.add(label + ": Archivo es obligatorio.");
            return "";
        }
        if (file.contains("../") || file.startsWith("/") || file.contains(":")) {
            errors.add(label + ": la ruta debe ser relativa dentro del ZIP.");
            return "";
        }
        return file;
    }

    private String stableId(String namespace, String... values) {
        StringBuilder source = new StringBuilder(namespace);
        for (String value : values) source.append(':').append(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
        return UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizePath(String value) { return value == null ? "" : value.replace('\\', '/').trim().toLowerCase(Locale.ROOT); }
    private void put(ObjectNode node, String field, String value) { node.put(field, value == null ? "" : value.trim()); }
    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT); }
    private record RowData(int rowNumber, Map<String, String> values) { String value(String key) { return values.getOrDefault(key, ""); } }
}
