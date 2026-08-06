import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputPath = process.argv[2];
if (!outputPath) throw new Error("Falta la ruta de salida");

const workbook = Workbook.create();
const datasets = {
  Fuentes: [
    ["Lote", "ArchivoPDF", "Seccion", "PaginaDesde", "PaginaHasta", "Observacion"],
    ["fixture-v2", "fixture.pdf", "Prueba", 1, 1, "Validación automatizada"],
  ],
  Productos: [
    ["CodigoFamilia", "ProductoId", "Version", "Nombre", "Descripcion", "Empresa", "EmpresaId", "Marca", "MarcaId", "Categoria", "CategoriaId", "Subcategoria", "SubcategoriaId", "Tipo", "Estado"],
    ["FIXTURE-001", null, null, "Perno de prueba", "Fixture del validador", "DINAFAST S.A.C.", "11111111-1111-4111-8111-111111111111", "DINA", "22222222-2222-4222-8222-222222222222", "Pernería", "33333333-3333-4333-8333-333333333333", "Pernos para moto", "44444444-4444-4444-8444-444444444441", "SINGLE", "DRAFT"],
  ],
  Variantes: [
    ["CodigoFamilia", "SKU", "CodigoProveedor", "NombreCorto", "Estado"],
    ["FIXTURE-001", "FIX-001", "FIX-001", "Perno M8", "ACTIVE"],
  ],
  Atributos: [
    ["CodigoFamilia", "SKU", "Atributo", "Valor", "Unidad"],
    ["FIXTURE-001", "FIX-001", "Diámetro", 8, "mm"],
  ],
  Presentaciones: [
    ["CodigoFamilia", "SKU", "Presentacion", "UnidadBase", "Equivalencia", "VentaMinima", "Incremento", "PermiteDecimales", "Estado"],
    ["FIXTURE-001", null, "Unidad", "UND", 1, 1, 1, "NO", "ACTIVE"],
  ],
  Precios: [
    ["CodigoFamilia", "SKU", "ListaPrecio", "Presentacion", "Moneda", "IGV", "Configuracion", "Precio", "Cotizar"],
    ["FIXTURE-001", "FIX-001", "DINA mayo 2026", "Unidad", "PEN", 18, "PRECIO_FIJO", 10, "NO"],
  ],
  Imagenes: [
    ["CodigoFamilia", "SKU", "Archivo", "Tipo", "Principal"],
  ],
};

for (const [sheetName, values] of Object.entries(datasets)) {
  const sheet = workbook.worksheets.add(sheetName);
  const range = sheet.getRangeByIndexes(0, 0, values.length, values[0].length);
  range.values = values;
  sheet.getRangeByIndexes(0, 0, 1, values[0].length).format = {
    fill: "#166534",
    font: { bold: true, color: "#FFFFFF" },
  };
  range.format.autofitColumns();
}

const xlsx = await SpreadsheetFile.exportXlsx(workbook);
await xlsx.save(outputPath);
