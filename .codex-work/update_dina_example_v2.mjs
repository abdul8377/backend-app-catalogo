import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const [inputPath, outputPath] = process.argv.slice(2);
if (!inputPath || !outputPath) throw new Error("Uso: node update_dina_example_v2.mjs <entrada> <salida>");

const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(inputPath));
const products = workbook.worksheets.getItem("Productos");
products.getRange("G2").values = [["11111111-1111-4111-8111-111111111111"]];
products.getRange("I2").values = [["22222222-2222-4222-8222-222222222222"]];
products.getRange("K2").values = [["33333333-3333-4333-8333-333333333333"]];
products.getRange("M2").values = [["44444444-4444-4444-8444-444444444442"]];
for (const column of ["G", "I", "K", "M"]) {
  products.getRange(`${column}1:${column}2`).format.columnWidth = 38;
}
products.getRange("A1:O2").format.wrapText = true;
products.getRange("A2:O2").format.rowHeight = 34;

const xlsx = await SpreadsheetFile.exportXlsx(workbook);
await xlsx.save(outputPath);
