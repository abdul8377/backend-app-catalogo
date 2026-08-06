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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
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

    public List<ProductImportCandidate> parse(byte[] workbookBytes) {
        return parse(workbookBytes, Set.of());
    }

    public List<ProductImportCandidate> parse(byte[] workbookBytes, Set<String> imageEntries) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            rejectFormulas(workbook);
            List<RowData> products = rows(requiredSheet(workbook, "Productos", "Products"));
            List<RowData> variants = rows(sheet(workbook, "Variantes", "Variants"));
            List<RowData> attributes = rows(sheet(workbook, "Atributos", "Attributes"));
            List<RowData> presentations = rows(sheet(workbook, "Presentaciones", "Presentations"));
            List<RowData> prices = rows(sheet(workbook, "Precios", "Prices"));
            List<RowData> images = rows(sheet(workbook, "Imagenes", "Images"));
            List<RowData> sources = rows(sheet(workbook, "Fuentes", "Sources"));

            int rowCount = products.size() + variants.size() + attributes.size()
                    + presentations.size() + prices.size() + images.size() + sources.size();
            if (rowCount > properties.maxRows()) {
                throw new BusinessRuleException("IMPORT_ROW_LIMIT",
                        "El libro supera el máximo de " + properties.maxRows() + " filas.");
            }
            return buildCandidates(products, variants, attributes, presentations, prices, images, imageEntries);
        } catch (IOException exception) {
            throw new BusinessRuleException("INVALID_XLSX", "No se pudo abrir el archivo XLSX.");
        }
    }

    private List<ProductImportCandidate> buildCandidates(
            List<RowData> products,
            List<RowData> variants,
            List<RowData> attributes,
            List<RowData> presentations,
            List<RowData> prices,
            List<RowData> images,
            Set<String> imageEntries) {

        Map<String, RowData> productByFamily = new LinkedHashMap<>();
        Set<String> familyCodes = new LinkedHashSet<>();
        collectFamilies(products, familyCodes);
        collectFamilies(variants, familyCodes);
        collectFamilies(attributes, familyCodes);
        collectFamilies(presentations, familyCodes);
        collectFamilies(prices, familyCodes);
        collectFamilies(images, familyCodes);

        for (RowData product : products) {
            String family = family(product);
            if (family.isBlank()) continue;
            RowData previous = productByFamily.putIfAbsent(family, product);
            if (previous != null) {
                throw new BusinessRuleException(
                        "DUPLICATE_PRODUCT_FAMILY",
                        "El código de familia " + family + " está repetido en Productos, filas "
                                + previous.rowNumber() + " y " + product.rowNumber() + ".");
            }
        }

        List<ProductImportCandidate> result = new ArrayList<>();
        for (String family : familyCodes) {
            List<String> warnings = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            RowData product = productByFamily.get(family);
            if (product == null) {
                errors.add("No existe una fila en Productos para la familia " + family + ".");
                product = new RowData(0, Map.of("codigofamilia", family));
            }

            Map<String, ObjectNode> variantBySku = new LinkedHashMap<>();
            ObjectNode aggregate = objectMapper.createObjectNode();
            text(aggregate, "code", family);
            text(aggregate, "name", product.value("nombre"));
            text(aggregate, "description", product.value("descripcion"));
            text(aggregate, "company", product.value("empresa"));
            text(aggregate, "companyId", product.value("empresaid"));
            text(aggregate, "brand", product.value("marca"));
            text(aggregate, "brandId", product.value("marcaid"));
            text(aggregate, "category", product.value("categoria"));
            text(aggregate, "categoryId", product.value("categoriaid"));
            text(aggregate, "subcategory", product.value("subcategoria"));
            text(aggregate, "subcategoryId", product.value("subcategoriaid"));
            text(aggregate, "productType", enumValue(product.value("tipo"),
                    ProductType.SINGLE.name(), ProductType.class, errors));
            text(aggregate, "status", enumValue(product.value("estado"),
                    ProductStatus.DRAFT.name(), ProductStatus.class, errors));

            ObjectNode commonAttributes = commonAttributes(family, attributes, warnings);
            ArrayNode variantArray = variants(family, variants, attributes, variantBySku, errors);
            ArrayNode presentationArray = presentations(family, presentations, variantBySku.keySet(), errors);
            ArrayNode priceArray = prices(family, prices, variantBySku, presentationArray, errors);
            ArrayNode imageArray = images(family, images, imageEntries, warnings, errors);

            aggregate.set("attributes", commonAttributes);
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

            validateRequiredProductFields(aggregate, variantArray, presentationArray, imageArray, warnings, errors);

            String productId = blankToNull(product.value("productoid"));
            Long version = integer(product.value("version"), "Version", errors);
            result.add(new ProductImportCandidate(
                    product.rowNumber(), family, productId, version, aggregate,
                    List.copyOf(warnings), List.copyOf(errors)));
        }

        if (result.isEmpty()) {
            throw new BusinessRuleException("EMPTY_IMPORT", "La hoja Productos no contiene filas para importar.");
        }
        return result;
    }

    private ObjectNode commonAttributes(String family, List<RowData> rows, List<String> warnings) {
        ObjectNode result = objectMapper.createObjectNode();
        for (RowData row : rowsFor(family, rows)) {
            if (!row.value("sku").isBlank()) continue;
            String name = row.value("atributo").trim();
            if (name.isBlank()) {
                warnings.add("Atributos fila " + row.rowNumber() + ": se omitió un atributo sin nombre.");
                continue;
            }
            ObjectNode value = result.putObject(name);
            value.put("value", row.value("valor").trim());
            value.put("unit", row.value("unidad").trim());
        }
        return result;
    }

    private ArrayNode variants(String family, List<RowData> variantRows, List<RowData> attributeRows,
                               Map<String, ObjectNode> variantBySku, List<String> errors) {
        ArrayNode result = objectMapper.createArrayNode();
        for (RowData row : rowsFor(family, variantRows)) {
            String sku = row.value("sku").trim().toUpperCase(Locale.ROOT);
            ObjectNode variant = result.addObject();
            variant.put("id", stableId("variant", family, sku));
            variant.put("sku", sku);
            variant.put("supplierCode", row.value("codigoproveedor").trim());
            variant.put("shortName", row.value("nombrecorto").trim());
            variant.put("status", defaultValue(row.value("estado"), "ACTIVE").toUpperCase(Locale.ROOT));
            variant.set("attributes", variantAttributes(family, sku, attributeRows));

            if (sku.isBlank()) {
                errors.add("Variantes fila " + row.rowNumber() + ": SKU es obligatorio.");
            } else if (variantBySku.putIfAbsent(sku, variant) != null) {
                errors.add("Variantes fila " + row.rowNumber() + ": el SKU " + sku + " está repetido.");
            }
            if (variant.path("shortName").asText().isBlank()) {
                errors.add("Variantes fila " + row.rowNumber() + ": NombreCorto es obligatorio.");
            }
        }
        return result;
    }

    private ObjectNode variantAttributes(String family, String sku, List<RowData> rows) {
        ObjectNode result = objectMapper.createObjectNode();
        for (RowData row : rowsFor(family, rows)) {
            if (!sku.equalsIgnoreCase(row.value("sku").trim())) continue;
            String name = row.value("atributo").trim();
            if (name.isBlank()) continue;
            ObjectNode value = result.putObject(name);
            value.put("value", row.value("valor").trim());
            value.put("unit", row.value("unidad").trim());
        }
        return result;
    }

    private ArrayNode presentations(String family, List<RowData> rows, Set<String> skus, List<String> errors) {
        ArrayNode result = objectMapper.createArrayNode();
        for (RowData row : rowsFor(family, rows)) {
            String sku = row.value("sku").trim().toUpperCase(Locale.ROOT);
            String name = row.value("presentacion").trim();
            if (!sku.isBlank() && !skus.contains(sku)) {
                errors.add("Presentaciones fila " + row.rowNumber() + ": el SKU " + sku + " no existe.");
            }
            ObjectNode presentation = result.addObject();
            presentation.put("id", stableId("presentation", family, sku, name));
            presentation.put("sku", sku);
            presentation.put("name", name);
            presentation.put("baseUnit", defaultValue(row.value("unidadbase"), "UND").toUpperCase(Locale.ROOT));
            decimal(presentation, "equivalence", defaultValue(row.value("equivalencia"), "1"),
                    "Presentaciones fila " + row.rowNumber() + " Equivalencia", errors);
            decimal(presentation, "minimumSale", defaultValue(row.value("ventaminima"), "1"),
                    "Presentaciones fila " + row.rowNumber() + " VentaMinima", errors);
            decimal(presentation, "purchaseIncrement", defaultValue(row.value("incremento"), "1"),
                    "Presentaciones fila " + row.rowNumber() + " Incremento", errors);
            presentation.put("allowsDecimals", yes(row.value("permitedecimales")));
            presentation.put("status", defaultValue(row.value("estado"), "ACTIVE").toUpperCase(Locale.ROOT));
            if (name.isBlank()) {
                errors.add("Presentaciones fila " + row.rowNumber() + ": Presentacion es obligatoria.");
            }
        }
        return result;
    }

    private ArrayNode prices(String family, List<RowData> rows, Map<String, ObjectNode> variantBySku,
                             ArrayNode presentations, List<String> errors) {
        ArrayNode result = objectMapper.createArrayNode();
        for (RowData row : rowsFor(family, rows)) {
            String sku = row.value("sku").trim().toUpperCase(Locale.ROOT);
            String presentationName = row.value("presentacion").trim();
            ObjectNode variant = variantBySku.get(sku);
            ObjectNode presentation = findPresentation(presentations, sku, presentationName);
            if (variant == null) {
                errors.add("Precios fila " + row.rowNumber() + ": el SKU " + sku + " no existe.");
            }
            if (presentation == null) {
                errors.add("Precios fila " + row.rowNumber() + ": la presentación "
                        + presentationName + " no corresponde al SKU " + sku + ".");
            }

            boolean quote = yes(row.value("cotizar"))
                    || Set.of("quote", "por_cotizar").contains(row.value("configuracion").trim().toLowerCase(Locale.ROOT));
            ObjectNode price = result.addObject();
            price.put("sku", sku);
            price.put("variantId", variant == null ? "" : variant.path("id").asText());
            price.put("priceList", defaultValue(row.value("listaprecio"), "General"));
            price.put("presentation", presentationName);
            price.put("presentationId", presentation == null ? "" : presentation.path("id").asText());
            price.put("currency", defaultValue(row.value("moneda"), "PEN").toUpperCase(Locale.ROOT));
            decimal(price, "taxRate", defaultValue(row.value("igv"), "18"),
                    "Precios fila " + row.rowNumber() + " IGV", errors);
            price.put("quoteRequired", quote);
            price.put("configuration", quote ? "por_cotizar" : "precio_fijo");
            if (quote) price.putNull("price");
            else decimal(price, "price", row.value("precio"),
                    "Precios fila " + row.rowNumber() + " Precio", errors);
        }
        return result;
    }

    private ArrayNode images(String family, List<RowData> rows, Set<String> imageEntries,
                             List<String> warnings, List<String> errors) {
        Set<String> normalizedArchive = new LinkedHashSet<>();
        imageEntries.forEach(entry -> normalizedArchive.add(normalizePath(entry)));
        ArrayNode result = objectMapper.createArrayNode();
        boolean primaryAlreadySelected = false;
        for (RowData row : rowsFor(family, rows)) {
            String fileName = relativePath(row.value("archivo"), row.rowNumber(), errors);
            if (fileName.isBlank()) continue;
            if (imageEntries.isEmpty()) {
                errors.add("Imágenes fila " + row.rowNumber() + ": adjunta un ZIP que contenga " + fileName + ".");
            } else if (!normalizedArchive.contains(normalizePath(fileName))) {
                errors.add("Imágenes fila " + row.rowNumber() + ": " + fileName + " no existe dentro del ZIP.");
            }

            boolean requestedPrimary = yes(row.value("principal"));
            ObjectNode image = result.addObject();
            image.put("sku", row.value("sku").trim().toUpperCase(Locale.ROOT));
            image.put("sourceFile", fileName);
            image.put("type", defaultValue(row.value("tipo"), "PRODUCT"));
            image.put("primary", requestedPrimary && !primaryAlreadySelected);
            if (requestedPrimary && primaryAlreadySelected) {
                warnings.add("Imágenes fila " + row.rowNumber()
                        + ": solo se conservará la primera imagen principal de " + family + ".");
            }
            primaryAlreadySelected |= requestedPrimary;
        }
        if (!primaryAlreadySelected && result.size() > 0 && result.get(0) instanceof ObjectNode first) {
            first.put("primary", true);
        }
        return result;
    }

    private ObjectNode salesConfiguration(ArrayNode presentations, Map<String, ObjectNode> variants) {
        ObjectNode configuration = objectMapper.createObjectNode();
        ArrayNode rows = configuration.putArray("presentations");
        for (JsonNode raw : presentations) {
            ObjectNode presentation = (ObjectNode) raw;
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
        configuration.put("uses_logistics_packages", false);
        configuration.putArray("logistics_packages");
        configuration.put("has_product_content", false);
        configuration.putArray("content_items");
        return configuration;
    }

    private ObjectNode pricingConfiguration(ArrayNode prices, ArrayNode presentations,
                                             Map<String, ObjectNode> variants) {
        ObjectNode configuration = objectMapper.createObjectNode();
        Map<String, String> listIds = new LinkedHashMap<>();
        for (JsonNode price : prices) {
            String listName = price.path("priceList").asText("General");
            listIds.putIfAbsent(listName, stableId("price-list", listName));
        }

        ArrayNode lists = configuration.putArray("lists");
        listIds.forEach((name, id) -> {
            ObjectNode list = lists.addObject();
            list.put("id", id);
            list.put("name", name);
            list.put("currency_code", currencyForList(prices, name));
            list.put("includes_igv", true);
            list.put("valid_from", LocalDate.now().toString());
            list.putNull("valid_until");
        });

        ArrayNode configuredPrices = configuration.putArray("prices");
        for (JsonNode price : prices) {
            ObjectNode row = configuredPrices.addObject();
            row.put("list_id", listIds.get(price.path("priceList").asText("General")));
            row.put("variant_id", price.path("variantId").asText());
            row.put("presentation_id", price.path("presentationId").asText());
            row.put("configuration", price.path("quoteRequired").asBoolean(false) ? "quote" : "fixed");
            if (price.path("quoteRequired").asBoolean(false)) row.putNull("fixed_price");
            else row.set("fixed_price", price.path("price"));
            row.putArray("ranges");
        }

        ArrayNode combinations = configuration.putArray("sellable_combinations");
        for (JsonNode rawPresentation : presentations) {
            ObjectNode presentation = (ObjectNode) rawPresentation;
            String sku = presentation.path("sku").asText();
            List<ObjectNode> assignedVariants;
            if (sku.isBlank()) assignedVariants = new ArrayList<>(variants.values());
            else if (variants.containsKey(sku)) assignedVariants = List.of(variants.get(sku));
            else assignedVariants = List.of();

            for (ObjectNode variant : assignedVariants) {
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
        return configuration;
    }

    private ObjectNode findPresentation(ArrayNode presentations, String sku, String name) {
        for (JsonNode raw : presentations) {
            if (!(raw instanceof ObjectNode presentation)) continue;
            String presentationSku = presentation.path("sku").asText();
            boolean sameSku = presentationSku.isBlank() || presentationSku.equalsIgnoreCase(sku);
            if (sameSku && presentation.path("name").asText().equalsIgnoreCase(name)) return presentation;
        }
        return null;
    }

    private String currencyForList(ArrayNode prices, String listName) {
        for (JsonNode price : prices) {
            if (price.path("priceList").asText().equals(listName)) return price.path("currency").asText("PEN");
        }
        return "PEN";
    }

    private void validateRequiredProductFields(ObjectNode aggregate, ArrayNode variants, ArrayNode presentations,
                                               ArrayNode images, List<String> warnings, List<String> errors) {
        if (aggregate.path("name").asText().isBlank()) errors.add("Nombre es obligatorio.");
        if (aggregate.path("company").asText().isBlank()) errors.add("Empresa es obligatoria.");
        if (aggregate.path("brand").asText().isBlank()) errors.add("Marca es obligatoria.");
        if (aggregate.path("category").asText().isBlank()) errors.add("Categoría es obligatoria.");
        if (variants.size() == 0) errors.add("Debe existir al menos una variante.");
        if (presentations.size() == 0) errors.add("Debe existir al menos una presentación.");
        if (images.size() == 0) warnings.add("El producto no tiene imágenes declaradas.");
    }

    private void collectFamilies(List<RowData> rows, Set<String> target) {
        rows.stream().map(this::family).filter(value -> !value.isBlank()).forEach(target::add);
    }

    private String family(RowData row) {
        return row.value("codigofamilia").trim().toUpperCase(Locale.ROOT);
    }

    private List<RowData> rowsFor(String family, List<RowData> rows) {
        return rows.stream().filter(row -> family.equalsIgnoreCase(family(row))).toList();
    }

    private List<RowData> rows(Sheet sheet) {
        if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) return List.of();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) return List.of();

        Map<Integer, String> columns = new LinkedHashMap<>();
        for (Cell cell : header) columns.put(cell.getColumnIndex(), normalizeHeader(formatter.formatCellValue(cell)));
        List<RowData> result = new ArrayList<>();
        for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean any = false;
            for (Map.Entry<Integer, String> column : columns.entrySet()) {
                Cell cell = row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                values.put(column.getValue(), value);
                any |= !value.isBlank();
            }
            if (any) result.add(new RowData(rowIndex + 1, values));
        }
        return result;
    }

    private void rejectFormulas(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw new BusinessRuleException("FORMULAS_NOT_ALLOWED",
                                "No se permiten fórmulas (" + sheet.getSheetName() + "!" + cell.getAddress() + ").");
                    }
                }
            }
        }
    }

    private Sheet requiredSheet(Workbook workbook, String... names) {
        Sheet sheet = sheet(workbook, names);
        if (sheet == null) throw new BusinessRuleException("MISSING_PRODUCTS_SHEET", "Falta la hoja Productos.");
        return sheet;
    }

    private Sheet sheet(Workbook workbook, String... names) {
        for (String name : names) {
            Sheet sheet = workbook.getSheet(name);
            if (sheet != null) return sheet;
        }
        return null;
    }

    private <E extends Enum<E>> String enumValue(String value, String fallback, Class<E> enumClass,
                                                 List<String> errors) {
        String normalized = defaultValue(value, fallback).toUpperCase(Locale.ROOT);
        try {
            Enum.valueOf(enumClass, normalized);
            return normalized;
        } catch (IllegalArgumentException exception) {
            errors.add("Valor no reconocido: " + value + ".");
            return fallback;
        }
    }

    private Long integer(String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim().replace(',', '.')).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            errors.add(label + " debe ser un entero.");
            return null;
        }
    }

    private void decimal(ObjectNode target, String field, String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) {
            target.putNull(field);
            errors.add(label + " es obligatorio.");
            return;
        }
        try {
            target.put(field, new BigDecimal(value.trim().replace(',', '.')));
        } catch (NumberFormatException exception) {
            target.putNull(field);
            errors.add(label + " debe ser numérico.");
        }
    }

    private String relativePath(String value, int rowNumber, List<String> errors) {
        String normalized = value == null ? "" : value.replace('\\', '/').trim();
        if (normalized.isBlank()) {
            errors.add("Imágenes fila " + rowNumber + ": Archivo es obligatorio.");
            return "";
        }
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")
                || normalized.contains(":")) {
            errors.add("Imágenes fila " + rowNumber + ": la ruta debe ser relativa dentro del ZIP.");
            return "";
        }
        return normalized;
    }

    private String stableId(String namespace, String... values) {
        StringBuilder source = new StringBuilder(namespace);
        for (String value : values) source.append(':').append(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
        return UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean yes(String value) {
        return Set.of("si", "sí", "true", "1", "x").contains(
                value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeHeader(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private void text(ObjectNode target, String field, String value) {
        target.put(field, value == null ? "" : value.trim());
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RowData(int rowNumber, Map<String, String> values) {
        String value(String key) {
            return values.getOrDefault(key, "");
        }
    }
}
