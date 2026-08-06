import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const [referencesPath, productsPath, outputDir] = process.argv.slice(2);
if (!referencesPath || !productsPath || !outputDir) {
  throw new Error("Uso: node inspect_workbooks.mjs <referencias.xlsx> <productos.xlsx> <salida>");
}

await fs.mkdir(outputDir, { recursive: true });

for (const [label, workbookPath] of [
  ["references", referencesPath],
  ["products", productsPath],
]) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(workbookPath));
  const sheetNames = workbook.worksheets.items.map((sheet) => sheet.name);
  const report = { file: workbookPath, sheets: [] };

  for (const sheetName of sheetNames) {
    const sheet = workbook.worksheets.getItem(sheetName);
    const inspected = await workbook.inspect({
      kind: "table",
      range: `'${sheetName.replaceAll("'", "''")}'!A1:Z40`,
      include: "values,formulas",
      tableMaxRows: 40,
      tableMaxCols: 26,
    });
    report.sheets.push({ name: sheetName, inspection: inspected });

    const preview = await workbook.render({
      sheetName,
      range: "A1:Z40",
      autoCrop: "all",
      scale: 1,
      format: "png",
    });
    const safeName = sheetName.replace(/[^a-zA-Z0-9_-]+/g, "_");
    await fs.writeFile(
      path.join(outputDir, `${label}_${safeName}.png`),
      new Uint8Array(await preview.arrayBuffer()),
    );
  }

  await fs.writeFile(
    path.join(outputDir, `${label}_inspection.json`),
    JSON.stringify(report, null, 2),
    "utf8",
  );
}
