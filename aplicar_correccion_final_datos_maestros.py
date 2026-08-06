from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil
import sys

ROOT = Path.cwd()
SERVICE_RELATIVE = Path(
    "src/main/java/com/abdul/catalogo/masterdata/service/"
    "MasterDataImportService.java"
)
MIGRATIONS_RELATIVE = Path("src/main/resources/db/migration")
MIGRATION_NAME = "V13__align_master_import_row_number.sql"
MIGRATION_SQL = """-- Alinea la columna creada en V11 con el nombre usado por MasterDataImportService.
-- No se modifica V11 porque las migraciones Flyway ya aplicadas son inmutables.
ALTER TABLE master_import_rows RENAME COLUMN num_row TO row_number;
"""


def backend_root() -> Path:
    for candidate in (ROOT / "backend-app-catalogo", ROOT):
        if (candidate / SERVICE_RELATIVE).exists() and (candidate / "pom.xml").exists():
            return candidate
    raise SystemExit(
        "No se encontró el backend. Ejecuta este script desde la raíz del "
        "repositorio combinado o desde backend-app-catalogo."
    )


BACKEND = backend_root()
TARGET = BACKEND / SERVICE_RELATIVE
MIGRATION = BACKEND / MIGRATIONS_RELATIVE / MIGRATION_NAME
BACKUP_ROOT = (
    BACKEND / ".correction_backups" / datetime.now().strftime("%Y%m%d_%H%M%S")
)


def read(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"No se encontró el archivo: {path}")
    return path.read_text(encoding="utf-8")


def backup(path: Path) -> None:
    if not path.exists():
        return
    destination = BACKUP_ROOT / path.relative_to(BACKEND)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"Modificado: {path.relative_to(BACKEND)}")


def replace_once(content: str, old: str, new: str, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"No se pudo aplicar '{label}'. "
            f"Se esperaba 1 coincidencia y se encontraron {count}. "
            "El archivo puede haber cambiado."
        )
    return content.replace(old, new, 1)


def replace_unless_applied(
    content: str,
    old: str,
    new: str,
    marker: str,
    label: str,
) -> str:
    if marker in content:
        print(f"Ya aplicada: {label}")
        return content
    return replace_once(content, old, new, label)


def patch_service() -> None:
    content = read(TARGET)

    content = replace_unless_applied(
        content,
        """    private static final Set<String> ATTRIBUTE_TYPES = Set.of(
            "texto_corto", "numero", "numero_unidad", "lista_unica", "lista_multiple", "si_no");
""",
        """    private static final Set<String> ATTRIBUTE_TYPES = Set.of(
            "texto_corto", "numero", "numero_unidad", "lista_unica", "lista_multiple", "si_no");
    private static final Set<String> CAPTURE_LEVELS = Set.of("familia", "variante", "decidir");
    private static final int MAX_ROWS = 10_000;
""",
        "private static final int MAX_ROWS = 10_000;",
        "agregar enums y límite de filas",
    )

    content = replace_unless_applied(
        content,
        """            parseAttributeUnits(workbook, context, result);
            parsePriceLists(workbook, context, result);
            if (result.isEmpty()) {
""",
        """            parseAttributeUnits(workbook, context, result);
            parsePriceLists(workbook, context, result);
            if (result.size() > MAX_ROWS) {
                throw new BusinessRuleException(
                        "MASTER_IMPORT_ROW_LIMIT",
                        "El libro supera el máximo de " + MAX_ROWS + " filas de datos maestros.");
            }
            if (result.isEmpty()) {
""",
        '"MASTER_IMPORT_ROW_LIMIT"',
        "limitar filas de datos maestros",
    )

    content = replace_unless_applied(
        content,
        """            decimal(payload, "factor_a_base", row.value("factorbase"), "1", errors, "FactorBase");
            integer(payload, "decimales", row.value("decimales"), 3, errors, "Decimales");
            payload.put("estado", active(row.value("estado")));
""",
        """            decimal(payload, "factor_a_base", row.value("factorbase"), "1", errors, "FactorBase");
            integer(payload, "decimales", row.value("decimales"), 3, errors, "Decimales");
            if (payload.path("factor_a_base").decimalValue().signum() <= 0) {
                errors.add("FactorBase debe ser mayor que cero.");
            }
            if (payload.path("decimales").asInt() < 0) {
                errors.add("Decimales no puede ser negativo.");
            }
            payload.put("estado", active(row.value("estado"), errors));
""",
        'errors.add("FactorBase debe ser mayor que cero.")',
        "validar FactorBase y Decimales",
    )

    content = replace_unless_applied(
        content,
        """            payload.put("clave", defaultValue(row.value("clave"), safeToken(name)));
            payload.put("tipo_dato", type); payload.put("nivel_captura", defaultValue(row.value("nivelcaptura"), "familia"));
            payload.put("requerido_activar", yes(row.value("requerido"))); payload.put("visible_ficha", !no(row.value("visibleficha")));
""",
        """            payload.put("clave", defaultValue(row.value("clave"), safeToken(name)));
            String captureLevel = defaultValue(row.value("nivelcaptura"), "familia").toLowerCase(Locale.ROOT);
            if (!CAPTURE_LEVELS.contains(captureLevel)) {
                errors.add("NivelCaptura no es válido: " + captureLevel + ".");
            }
            payload.put("tipo_dato", type); payload.put("nivel_captura", captureLevel);
            payload.put("requerido_activar", yes(row.value("requerido"))); payload.put("visible_ficha", !no(row.value("visibleficha")));
""",
        '"NivelCaptura no es válido: "',
        "validar NivelCaptura",
    )

    if "private boolean active(String value, List<String> errors)" not in content:
        occurrences = content.count('active(row.value("estado"))')
        if occurrences < 8:
            raise SystemExit(
                "No se encontraron suficientes usos de active(Estado). "
                f"Encontrados: {occurrences}. El archivo puede haber cambiado."
            )
        content = content.replace(
            'active(row.value("estado"))',
            'active(row.value("estado"), errors)',
        )
        print(f"Actualizados {occurrences} usos de Estado.")
    else:
        print("Ya aplicada: validación estricta de Estado")

    content = replace_unless_applied(
        content,
        """    private boolean yes(String value) { return Set.of("si", "sí", "1", "true", "yes", "activo").contains(masters.normalize(value)); }
    private boolean no(String value) { return Set.of("no", "0", "false", "inactivo").contains(masters.normalize(value)); }
    private boolean active(String value) { return value == null || value.isBlank() || !no(value); }
""",
        """    private boolean yes(String value) { return Set.of("si", "sí", "1", "true", "yes", "activo").contains(masters.normalize(value)); }
    private boolean no(String value) { return Set.of("no", "0", "false", "inactivo").contains(masters.normalize(value)); }
    private boolean active(String value, List<String> errors) {
        if (value == null || value.isBlank()) return true;
        if (yes(value)) return true;
        if (no(value)) return false;
        errors.add("Estado debe ser SI/NO, ACTIVO/INACTIVO, 1/0 o TRUE/FALSE.");
        return true;
    }
""",
        "private boolean active(String value, List<String> errors)",
        "rechazar estados desconocidos",
    )

    content = replace_unless_applied(
        content,
        """            help(instructions, "Jerarquía", "RutaPadre y RutaCategoria usan >, por ejemplo Línea Moto > Pernos para moto.");
            help(instructions, "Relación de marca", "Relaciona la marca con la categoría principal. Las subcategorías heredan esa relación.");
""",
        """            help(instructions, "Jerarquía", "RutaPadre y RutaCategoria usan >, por ejemplo Línea Moto > Pernos para moto.");
            help(instructions, "Orden de categorías", "En Categorias, coloca cada fila padre antes de sus hijas.");
            help(instructions, "Valores permitidos", "Estado: SI/NO o ACTIVO/INACTIVO. TipoDato: texto_corto, numero, numero_unidad, lista_unica, lista_multiple o si_no. NivelCaptura: familia, variante o decidir.");
            help(instructions, "Relación de marca", "Relaciona la marca con la categoría principal. Las subcategorías heredan esa relación.");
""",
        '"Orden de categorías"',
        "documentar orden y valores permitidos",
    )

    backup(TARGET)
    write(TARGET, content)


def create_migration() -> None:
    if MIGRATION.exists():
        current = read(MIGRATION).replace("\r\n", "\n")
        if current.strip() != MIGRATION_SQL.strip():
            raise SystemExit(
                f"Ya existe {MIGRATION_NAME} con otro contenido. "
                "No se sobrescribió para evitar alterar el historial Flyway."
            )
        print(f"Ya existe correctamente: {MIGRATION.relative_to(BACKEND)}")
        return

    # Verificaciones para evitar crear una migración incoherente.
    v11 = BACKEND / MIGRATIONS_RELATIVE / "V11__relational_master_data_and_import.sql"
    v11_content = read(v11)
    service_content = read(TARGET)
    if "num_row INT NOT NULL" not in v11_content:
        raise SystemExit("V11 ya no contiene num_row; revisa manualmente las migraciones.")
    if "row_number" not in service_content:
        raise SystemExit("MasterDataImportService no utiliza row_number como se esperaba.")

    write(MIGRATION, MIGRATION_SQL)


def main() -> None:
    print(f"Backend detectado: {BACKEND}")
    print(f"Copias de seguridad: {BACKUP_ROOT.relative_to(BACKEND)}")
    patch_service()
    create_migration()

    print("\nCorrección final de datos maestros aplicada.")
    print("\nEjecuta en PowerShell:")
    print("  .\\mvnw.cmd clean test")
    print("\nDespués inicia el backend para que Flyway aplique V13:")
    print("  .\\mvnw.cmd spring-boot:run")
    print("\nDescarga una plantilla nueva de datos maestros antes de importar.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"\nERROR: {exception}", file=sys.stderr)
        raise
