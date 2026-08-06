package com.abdul.catalogo.masterdata.service;

import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.repository.SyncRecordRepository;
import com.abdul.catalogo.synchronization.service.ServerChangePublisher;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MasterDataImportService {
    public static final String TEMPLATE_VERSION = "1.0";
    private static final List<String> ORDER = List.of(
            "COMPANY", "BRAND", "CATEGORY", "BRAND_CATEGORY", "MEASUREMENT_UNIT",
            "CATEGORY_ATTRIBUTE", "CATEGORY_ATTRIBUTE_OPTION", "CATEGORY_ATTRIBUTE_UNIT", "PRICE_LIST");
    private static final Set<String> ATTRIBUTE_TYPES = Set.of(
            "texto_corto", "numero", "numero_unidad", "lista_unica", "lista_multiple", "si_no");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RelationalMasterDataService masters;
    private final ServerChangePublisher publisher;
    private final SyncRecordRepository records;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public MasterDataImportService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                   RelationalMasterDataService masters, ServerChangePublisher publisher,
                                   SyncRecordRepository records) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.masters = masters;
        this.publisher = publisher;
        this.records = records;
    }

    @Transactional
    public ImportPreview preview(MultipartFile file, String actor) {
        byte[] bytes = validateAndRead(file);
        String hash = Digests.sha256(bytes);
        Optional<String> existing = one("SELECT id FROM master_imports WHERE file_hash = ?", hash);
        if (existing.isPresent()) return get(existing.get());

        String importId = UUID.randomUUID().toString();
        List<Candidate> candidates;
        try {
            candidates = parse(bytes);
        } catch (BusinessRuleException exception) {
            candidates = List.of(new Candidate(0, "Archivo", "FILE", "file-error", "REJECT", "ERROR",
                    objectMapper.createObjectNode(), List.of(exception.getMessage())));
        }
        int valid = 0, warnings = 0, errors = 0;
        for (Candidate candidate : candidates) {
            if (candidate.status().equals("ERROR")) errors++;
            else if (candidate.status().equals("WARNING")) warnings++;
            else valid++;
        }
        jdbc.update("""
                INSERT INTO master_imports(id, file_name, file_hash, status, total_rows, valid_rows,
                    warning_rows, error_rows, created_by, created_at)
                VALUES (?, ?, ?, 'PREVIEW_READY', ?, ?, ?, ?, ?, ?)
                """, importId, safeName(file.getOriginalFilename()), hash, candidates.size(), valid, warnings,
                errors, actor == null ? "admin" : actor, Instant.now());
        for (Candidate candidate : candidates) {
            jdbc.update("""
                    INSERT INTO master_import_rows(id, import_id, row_number, sheet_name, entity_type,
                        entity_id, action, status, payload_json, messages_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), importId, candidate.rowNumber(), candidate.sheet(),
                    candidate.entityType(), candidate.entityId(), candidate.action(), candidate.status(),
                    write(candidate.payload()), write(candidate.messages()));
        }
        return get(importId);
    }

    @Transactional
    public ImportPreview confirm(String importId) {
        Map<String, Object> header = jdbc.query("SELECT * FROM master_imports WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rowMap(rs) : null, importId);
        if (header == null) {
            throw new ResourceNotFoundException("MASTER_IMPORT_NOT_FOUND", "La importación de maestros no existe.");
        }
        if (((Number) header.get("error_rows")).intValue() > 0) {
            throw new BusinessRuleException("MASTER_IMPORT_HAS_ERRORS",
                    "Corrige las filas con error antes de confirmar la carga de maestros.");
        }
        if ("CONFIRMED".equals(header.get("status"))) return get(importId);

        List<StoredRow> rows = jdbc.query("""
                SELECT id, row_number, sheet_name, entity_type, entity_id, action, status,
                       payload_json, messages_json, result_version, result_sequence
                FROM master_import_rows WHERE import_id = ?
                """, (rs, index) -> new StoredRow(rs.getString("id"), rs.getInt("row_number"),
                rs.getString("sheet_name"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("action"), rs.getString("status"), rs.getString("payload_json"),
                rs.getString("messages_json"), number(rs.getObject("result_version")),
                number(rs.getObject("result_sequence"))), importId);
        rows = rows.stream().sorted(Comparator
                .comparingInt((StoredRow row) -> ORDER.indexOf(row.entityType()))
                .thenComparingInt(StoredRow::rowNumber)).toList();

        for (StoredRow row : rows) {
            long expectedVersion = records.findByEntityTypeAndEntityId(row.entityType(), row.entityId())
                    .map(record -> record.getVersion()).orElse(0L);
            JsonNode payload = read(row.payloadJson());
            var published = publisher.publish(row.entityType(), row.entityId(), SyncOperation.UPSERT,
                    payload, "server-master-import", expectedVersion);
            jdbc.update("UPDATE master_import_rows SET result_version = ?, result_sequence = ? WHERE id = ?",
                    published.version(), published.sequence(), row.id());
        }
        jdbc.update("UPDATE master_imports SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                Instant.now(), importId);
        return get(importId);
    }

    @Transactional(readOnly = true)
    public ImportPreview get(String importId) {
        Map<String, Object> header = jdbc.query("SELECT * FROM master_imports WHERE id = ?",
                rs -> rs.next() ? rowMap(rs) : null, importId);
        if (header == null) {
            throw new ResourceNotFoundException("MASTER_IMPORT_NOT_FOUND", "La importación de maestros no existe.");
        }
        List<ImportRow> rows = jdbc.query("""
                SELECT row_number, sheet_name, entity_type, entity_id, action, status,
                       messages_json, result_version, result_sequence
                FROM master_import_rows WHERE import_id = ?
                ORDER BY row_number, sheet_name
                """, (rs, index) -> new ImportRow(rs.getInt("row_number"), rs.getString("sheet_name"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("action"),
                rs.getString("status"), strings(rs.getString("messages_json")),
                number(rs.getObject("result_version")), number(rs.getObject("result_sequence"))), importId);
        return new ImportPreview(importId, header.get("file_name").toString(), header.get("status").toString(),
                ((Number) header.get("total_rows")).intValue(), ((Number) header.get("valid_rows")).intValue(),
                ((Number) header.get("warning_rows")).intValue(), ((Number) header.get("error_rows")).intValue(),
                timestamp(header.get("created_at")), timestamp(header.get("confirmed_at")), rows);
    }

    @Transactional(readOnly = true)
    public byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GOLD.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont(); font.setBold(true); header.setFont(font);

            sheet(workbook, header, "Empresas", "Id", "Nombre", "RUC", "Telefono", "Direccion", "Estado");
            sheet(workbook, header, "Marcas", "Id", "EmpresaId", "Empresa", "Nombre", "Estado");
            sheet(workbook, header, "Categorias", "Id", "RutaPadre", "Nombre", "Descripcion", "Estado");
            sheet(workbook, header, "MarcaCategorias", "Id", "Empresa", "MarcaId", "Marca", "CategoriaId", "RutaCategoria", "Estado");
            sheet(workbook, header, "Unidades", "Id", "Codigo", "Nombre", "Simbolo", "Magnitud", "FactorBase", "Decimales", "Estado");
            sheet(workbook, header, "Atributos", "Id", "CategoriaId", "RutaCategoria", "Nombre", "Clave", "TipoDato",
                    "NivelCaptura", "Requerido", "VisibleFicha", "Filtrable", "PuedeSerEje", "Orden", "Estado");
            sheet(workbook, header, "Opciones", "Id", "AtributoId", "RutaCategoria", "Atributo", "Etiqueta", "Codigo", "Orden", "Estado");
            sheet(workbook, header, "AtributoUnidades", "Id", "AtributoId", "RutaCategoria", "Atributo", "UnidadId", "UnidadCodigo", "Predeterminada", "Orden", "Estado");
            sheet(workbook, header, "ListasPrecio", "Id", "Nombre", "Moneda", "IncluyeIGV", "IGV", "Estado");
            Sheet instructions = sheet(workbook, header, "Instrucciones", "Tema", "Detalle");
            help(instructions, "Flujo", "Descarga, completa, carga para vista previa y confirma solo cuando no existan errores.");
            help(instructions, "Identidades", "Id es opcional. Si queda vacío, el servidor reutiliza el registro por su clave natural o genera un UUID estable.");
            help(instructions, "Jerarquía", "RutaPadre y RutaCategoria usan >, por ejemplo Línea Moto > Pernos para moto.");
            help(instructions, "Relación de marca", "Relaciona la marca con la categoría principal. Las subcategorías heredan esa relación.");
            help(instructions, "Moneda", "La moneda está fijada en PEN. Cualquier otro código bloquea la importación.");
            help(instructions, "Sincronización", "Al confirmar, cada fila publica un cambio normal y queda disponible para la tablet.");

            appendReferences(workbook, header);
            workbook.setActiveSheet(workbook.getSheetIndex("Empresas"));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar la plantilla de maestros.", exception);
        }
    }

    private List<Candidate> parse(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            rejectFormulas(workbook);
            ParseContext context = new ParseContext();
            List<Candidate> result = new ArrayList<>();
            parseCompanies(workbook, context, result);
            parseBrands(workbook, context, result);
            parseCategories(workbook, context, result);
            parseBrandCategories(workbook, context, result);
            parseUnits(workbook, context, result);
            parseAttributes(workbook, context, result);
            parseOptions(workbook, context, result);
            parseAttributeUnits(workbook, context, result);
            parsePriceLists(workbook, context, result);
            if (result.isEmpty()) {
                throw new BusinessRuleException("EMPTY_MASTER_IMPORT", "El Excel no contiene filas de datos maestros.");
            }
            return result;
        } catch (IOException exception) {
            throw new BusinessRuleException("INVALID_MASTER_XLSX", "No se pudo abrir el archivo XLSX de maestros.");
        }
    }

    private void parseCompanies(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "Empresas")) {
            List<String> errors = new ArrayList<>();
            String name = required(row, "nombre", errors);
            String id = first(row.value("id"), masters.findCompanyId(name).orElse(null), stableId("company", masters.normalize(name)));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); payload.put("nombre", name); payload.put("ruc", row.value("ruc"));
            payload.put("telefono", row.value("telefono")); payload.put("direccion", row.value("direccion"));
            payload.put("estado", active(row.value("estado")));
            context.companies.put(masters.normalize(name), id);
            add(output, row, "Empresas", "COMPANY", id, payload, errors);
        }
    }

    private void parseBrands(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "Marcas")) {
            List<String> errors = new ArrayList<>();
            String companyId = resolveCompany(row.value("empresaid"), row.value("empresa"), context, errors);
            String name = required(row, "nombre", errors);
            String id = first(row.value("id"), companyId == null ? null : masters.findBrandId(companyId, name).orElse(null),
                    stableId("brand", companyId, masters.normalize(name)));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); put(payload, "empresa_id", companyId); payload.put("nombre", name);
            payload.put("estado", active(row.value("estado")));
            if (companyId != null) context.brands.put(companyId + "|" + masters.normalize(name), id);
            add(output, row, "Marcas", "BRAND", id, payload, errors);
        }
    }

    private void parseCategories(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "Categorias")) {
            List<String> errors = new ArrayList<>();
            String parentPath = cleanPath(row.value("rutapadre"));
            String parentId = parentPath.isBlank() ? null : resolveCategoryPath(parentPath, context, errors);
            String name = required(row, "nombre", errors);
            String fullPath = parentPath.isBlank() ? name : parentPath + " > " + name;
            String existing = parentPath.isBlank() ? masters.findRootCategoryId(name).orElse(null)
                    : parentId == null ? null : masters.findChildCategoryId(parentId, name).orElse(null);
            String id = first(row.value("id"), existing, stableId("category", masters.normalize(fullPath)));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); put(payload, "categoria_padre_id", parentId); payload.put("nombre", name);
            payload.put("descripcion", row.value("descripcion")); payload.put("estado", active(row.value("estado")));
            context.categories.put(masters.normalize(fullPath), id);
            add(output, row, "Categorias", "CATEGORY", id, payload, errors);
        }
    }

    private void parseBrandCategories(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "MarcaCategorias")) {
            List<String> errors = new ArrayList<>();
            String brandId = row.value("marcaid");
            if (brandId.isBlank()) {
                String companyId = resolveCompany("", row.value("empresa"), context, errors);
                brandId = companyId == null ? null : resolveBrand(companyId, row.value("marca"), context, errors);
            }
            String categoryId = row.value("categoriaid");
            if (categoryId.isBlank()) categoryId = resolveCategoryPath(row.value("rutacategoria"), context, errors);
            String id = first(row.value("id"), stableId("brand-category", brandId, categoryId));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); put(payload, "marca_id", brandId); put(payload, "categoria_id", categoryId);
            payload.put("estado", active(row.value("estado")));
            add(output, row, "MarcaCategorias", "BRAND_CATEGORY", id, payload, errors);
        }
    }

    private void parseUnits(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "Unidades")) {
            List<String> errors = new ArrayList<>();
            String code = required(row, "codigo", errors);
            String id = first(row.value("id"), masters.findMeasurementUnitId(code).orElse(null),
                    "unit-" + safeToken(code));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); payload.put("codigo", code); payload.put("nombre", required(row, "nombre", errors));
            payload.put("simbolo", required(row, "simbolo", errors)); payload.put("magnitud", required(row, "magnitud", errors));
            decimal(payload, "factor_a_base", row.value("factorbase"), "1", errors, "FactorBase");
            integer(payload, "decimales", row.value("decimales"), 3, errors, "Decimales");
            payload.put("estado", active(row.value("estado")));
            context.units.put(masters.normalize(code), id);
            add(output, row, "Unidades", "MEASUREMENT_UNIT", id, payload, errors);
        }
    }

    private void parseAttributes(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "Atributos")) {
            List<String> errors = new ArrayList<>();
            String categoryId = first(row.value("categoriaid"), resolveCategoryPath(row.value("rutacategoria"), context, errors));
            String name = required(row, "nombre", errors);
            String type = defaultValue(row.value("tipodato"), "texto_corto").toLowerCase(Locale.ROOT);
            if (!ATTRIBUTE_TYPES.contains(type)) errors.add("TipoDato no es válido: " + type + ".");
            String id = first(row.value("id"), categoryId == null ? null : masters.findCategoryAttributeId(categoryId, name).orElse(null),
                    stableId("attribute", categoryId, masters.normalize(name)));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); put(payload, "categoria_id", categoryId); payload.put("nombre", name);
            payload.put("clave", defaultValue(row.value("clave"), safeToken(name)));
            payload.put("tipo_dato", type); payload.put("nivel_captura", defaultValue(row.value("nivelcaptura"), "familia"));
            payload.put("requerido_activar", yes(row.value("requerido"))); payload.put("visible_ficha", !no(row.value("visibleficha")));
            payload.put("filtrable", yes(row.value("filtrable"))); payload.put("puede_ser_eje", yes(row.value("puedesereje")));
            integer(payload, "orden", row.value("orden"), 0, errors, "Orden"); payload.put("estado", active(row.value("estado")));
            if (categoryId != null) context.attributes.put(categoryId + "|" + masters.normalize(name), id);
            add(output, row, "Atributos", "CATEGORY_ATTRIBUTE", id, payload, errors);
        }
    }

    private void parseOptions(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "Opciones")) {
            List<String> errors = new ArrayList<>();
            String attributeId = resolveAttribute(row, context, errors);
            String label = required(row, "etiqueta", errors);
            String code = defaultValue(row.value("codigo"), safeToken(label));
            String id = first(row.value("id"), stableId("option", attributeId, masters.normalize(code)));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); put(payload, "categoria_atributo_id", attributeId);
            payload.put("etiqueta", label); payload.put("codigo", code);
            integer(payload, "orden", row.value("orden"), 0, errors, "Orden"); payload.put("estado", active(row.value("estado")));
            add(output, row, "Opciones", "CATEGORY_ATTRIBUTE_OPTION", id, payload, errors);
        }
    }

    private void parseAttributeUnits(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "AtributoUnidades")) {
            List<String> errors = new ArrayList<>();
            String attributeId = resolveAttribute(row, context, errors);
            String unitId = first(row.value("unidadid"), context.units.get(masters.normalize(row.value("unidadcodigo"))),
                    masters.findMeasurementUnitId(row.value("unidadcodigo")).orElse(null));
            if (unitId == null) errors.add("No se encontró la unidad indicada.");
            String id = first(row.value("id"), stableId("attribute-unit", attributeId, unitId));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); put(payload, "categoria_atributo_id", attributeId); put(payload, "unidad_medida_id", unitId);
            payload.put("es_predeterminada", yes(row.value("predeterminada")));
            integer(payload, "orden", row.value("orden"), 0, errors, "Orden"); payload.put("estado", active(row.value("estado")));
            add(output, row, "AtributoUnidades", "CATEGORY_ATTRIBUTE_UNIT", id, payload, errors);
        }
    }

    private void parsePriceLists(Workbook workbook, ParseContext context, List<Candidate> output) {
        for (RowData row : rows(workbook, "ListasPrecio")) {
            List<String> errors = new ArrayList<>();
            String name = required(row, "nombre", errors);
            String currency = defaultValue(row.value("moneda"), "PEN").toUpperCase(Locale.ROOT);
            if (!currency.equals("PEN")) errors.add("Moneda debe ser PEN.");
            String id = first(row.value("id"), masters.findPriceListId(name).orElse(null),
                    stableId("price-list", masters.normalize(name)));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id); payload.put("nombre", name); payload.put("moneda", "PEN");
            payload.put("incluye_igv", !no(row.value("incluyeigv")));
            decimal(payload, "igv_porcentaje", row.value("igv"), "18", errors, "IGV");
            payload.put("estado", active(row.value("estado")));
            context.priceLists.put(masters.normalize(name), id);
            add(output, row, "ListasPrecio", "PRICE_LIST", id, payload, errors);
        }
    }

    private String resolveCompany(String explicitId, String name, ParseContext context, List<String> errors) {
        String id = clean(explicitId);
        if (!id.isBlank()) return id;
        id = context.companies.get(masters.normalize(name));
        if (id == null) id = masters.findCompanyId(name).orElse(null);
        if (id == null) errors.add("No se encontró la empresa " + name + ".");
        return id;
    }

    private String resolveBrand(String companyId, String name, ParseContext context, List<String> errors) {
        String id = context.brands.get(companyId + "|" + masters.normalize(name));
        if (id == null) id = masters.findBrandId(companyId, name).orElse(null);
        if (id == null) errors.add("No se encontró la marca " + name + " dentro de la empresa indicada.");
        return id;
    }

    private String resolveCategoryPath(String path, ParseContext context, List<String> errors) {
        String cleanPath = cleanPath(path);
        if (cleanPath.isBlank()) {
            errors.add("RutaCategoria es obligatoria.");
            return null;
        }
        String id = context.categories.get(masters.normalize(cleanPath));
        if (id == null) id = masters.findCategoryByPath(cleanPath).orElse(null);
        if (id == null) errors.add("No se encontró la categoría " + cleanPath + ". Revisa el orden de las filas padre.");
        return id;
    }

    private String resolveAttribute(RowData row, ParseContext context, List<String> errors) {
        String explicit = clean(row.value("atributoid"));
        if (!explicit.isBlank()) return explicit;
        String categoryId = resolveCategoryPath(row.value("rutacategoria"), context, errors);
        String name = row.value("atributo");
        String id = categoryId == null ? null : context.attributes.get(categoryId + "|" + masters.normalize(name));
        if (id == null && categoryId != null) id = masters.findCategoryAttributeId(categoryId, name).orElse(null);
        if (id == null) errors.add("No se encontró el atributo " + name + ".");
        return id;
    }

    private void add(List<Candidate> output, RowData row, String sheet, String entityType,
                     String entityId, ObjectNode payload, List<String> errors) {
        String id = entityId == null || entityId.isBlank() ? stableId("invalid", sheet, Integer.toString(row.rowNumber())) : entityId;
        boolean exists = records.findByEntityTypeAndEntityId(entityType, id).isPresent();
        output.add(new Candidate(row.rowNumber(), sheet, entityType, id,
                errors.isEmpty() ? exists ? "UPDATE" : "CREATE" : "REJECT",
                errors.isEmpty() ? "VALID" : "ERROR", payload, List.copyOf(errors)));
    }

    private List<RowData> rows(Workbook workbook, String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) return List.of();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) return List.of();
        Map<Integer, String> headers = new LinkedHashMap<>();
        for (Cell cell : header) headers.put(cell.getColumnIndex(), key(formatter.formatCellValue(cell)));
        List<RowData> result = new ArrayList<>();
        for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean hasData = false;
            for (var entry : headers.entrySet()) {
                String value = formatter.formatCellValue(row.getCell(entry.getKey())).trim();
                values.put(entry.getValue(), value); hasData |= !value.isBlank();
            }
            if (hasData) result.add(new RowData(rowIndex + 1, values));
        }
        return result;
    }

    private void rejectFormulas(Workbook workbook) {
        for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA) {
                throw new BusinessRuleException("MASTER_FORMULA_NOT_ALLOWED",
                        "No se admiten fórmulas: " + sheet.getSheetName() + " fila " + (row.getRowNum() + 1) + ".");
            }
        }
    }

    private void appendReferences(XSSFWorkbook workbook, CellStyle header) {
        reference(workbook, header, "Ref_Empresas", "SELECT id, nombre, ruc FROM empresas WHERE deleted = FALSE ORDER BY nombre",
                "Id", "Nombre", "RUC");
        reference(workbook, header, "Ref_Marcas", "SELECT m.id, e.nombre empresa, m.nombre FROM marcas m JOIN empresas e ON e.id=m.empresa_id WHERE m.deleted=FALSE ORDER BY e.nombre,m.nombre",
                "Id", "Empresa", "Marca");
        reference(workbook, header, "Ref_Categorias", "SELECT id, nombre, categoria_padre_id FROM categorias WHERE deleted=FALSE ORDER BY nombre",
                "Id", "Nombre", "CategoriaPadreId");
        reference(workbook, header, "Ref_Unidades", "SELECT id, codigo, nombre, simbolo FROM unidades_medida WHERE deleted=FALSE ORDER BY codigo",
                "Id", "Codigo", "Nombre", "Simbolo");
        reference(workbook, header, "Ref_ListasPrecio", "SELECT id, nombre, moneda, igv_porcentaje FROM listas_precios WHERE deleted=FALSE ORDER BY nombre",
                "Id", "Nombre", "Moneda", "IGV");
    }

    private void reference(XSSFWorkbook workbook, CellStyle header, String name, String sql, String... columns) {
        Sheet sheet = sheet(workbook, header, name, columns);
        List<Map<String, Object>> data = jdbc.queryForList(sql);
        int rowIndex = 1;
        for (Map<String, Object> source : data) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            for (Object value : source.values()) row.createCell(column++).setCellValue(value == null ? "" : value.toString());
        }
    }

    private Sheet sheet(XSSFWorkbook workbook, CellStyle headerStyle, String name, String... headers) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            Cell cell = row.createCell(index); cell.setCellValue(headers[index]); cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, Math.min(60, Math.max(15, headers[index].length() + 4)) * 256);
        }
        sheet.createFreezePane(0, 1);
        if (headers.length > 0) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.length - 1));
        return sheet;
    }

    private void help(Sheet sheet, String topic, String detail) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        row.createCell(0).setCellValue(topic); row.createCell(1).setCellValue(detail);
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessRuleException("EMPTY_MASTER_FILE", "Selecciona un archivo XLSX.");
        if (file.getSize() > 20L * 1024L * 1024L) throw new BusinessRuleException("MASTER_FILE_TOO_LARGE", "El archivo supera 20 MB.");
        String name = safeName(file.getOriginalFilename());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessRuleException("INVALID_MASTER_EXTENSION", "Solo se aceptan archivos .xlsx sin macros.");
        }
        try { return file.getBytes(); }
        catch (IOException exception) { throw new BusinessRuleException("MASTER_READ_ERROR", "No se pudo leer el archivo."); }
    }

    private String safeName(String value) {
        if (value == null || value.isBlank()) return "maestros.xlsx";
        return Path.of(value.replace('\\', '/')).getFileName().toString();
    }

    private String stableId(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("|", parts).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String safeToken(String value) {
        return masters.normalize(value).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String key(String value) { return masters.normalize(value).replaceAll("[^a-z0-9]", ""); }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String cleanPath(String value) { return clean(value).replaceAll("\\s*>\\s*", " > "); }
    private String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return null; }
    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private boolean yes(String value) { return Set.of("si", "sí", "1", "true", "yes", "activo").contains(masters.normalize(value)); }
    private boolean no(String value) { return Set.of("no", "0", "false", "inactivo").contains(masters.normalize(value)); }
    private boolean active(String value) { return value == null || value.isBlank() || !no(value); }

    private String required(RowData row, String field, List<String> errors) {
        String value = row.value(field).trim();
        if (value.isBlank()) errors.add(field + " es obligatorio.");
        return value;
    }

    private void put(ObjectNode node, String name, String value) { if (value == null) node.putNull(name); else node.put(name, value); }
    private void decimal(ObjectNode node, String name, String value, String fallback, List<String> errors, String label) {
        try { node.put(name, new java.math.BigDecimal(defaultValue(value, fallback).replace(',', '.'))); }
        catch (NumberFormatException exception) { errors.add(label + " debe ser numérico."); node.put(name, new java.math.BigDecimal(fallback)); }
    }
    private void integer(ObjectNode node, String name, String value, int fallback, List<String> errors, String label) {
        try { node.put(name, value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim())); }
        catch (NumberFormatException exception) { errors.add(label + " debe ser entero."); node.put(name, fallback); }
    }

    private Optional<String> one(String sql, Object... args) {
        return jdbc.query(sql, rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(), args);
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("No se pudo serializar la importación.", exception); }
    }

    private JsonNode read(String json) {
        try { return objectMapper.readTree(json); }
        catch (JacksonException exception) { throw new IllegalStateException("Payload maestro inválido.", exception); }
    }

    private List<String> strings(String json) {
        JsonNode node = read(json); List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private Instant timestamp(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(value.toString());
    }

    private Map<String, Object> rowMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        var metadata = rs.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) result.put(metadata.getColumnLabel(i), rs.getObject(i));
        return result;
    }

    private record RowData(int rowNumber, Map<String, String> values) {
        String value(String name) { return values.getOrDefault(name, ""); }
    }
    private record Candidate(int rowNumber, String sheet, String entityType, String entityId,
                             String action, String status, ObjectNode payload, List<String> messages) { }
    private record StoredRow(String id, int rowNumber, String sheet, String entityType, String entityId,
                             String action, String status, String payloadJson, String messagesJson,
                             Long resultVersion, Long resultSequence) { }
    private static final class ParseContext {
        final Map<String, String> companies = new LinkedHashMap<>();
        final Map<String, String> brands = new LinkedHashMap<>();
        final Map<String, String> categories = new LinkedHashMap<>();
        final Map<String, String> units = new LinkedHashMap<>();
        final Map<String, String> attributes = new LinkedHashMap<>();
        final Map<String, String> priceLists = new LinkedHashMap<>();
    }

    public record ImportPreview(String id, String fileName, String status, int totalRows, int validRows,
                                int warningRows, int errorRows, Instant createdAt, Instant confirmedAt,
                                List<ImportRow> rows) { }
    public record ImportRow(int rowNumber, String sheet, String entityType, String entityId, String action,
                            String status, List<String> messages, Long resultVersion, Long resultSequence) { }
}
