from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil
import sys

ROOT = Path.cwd()
RELATIVE = Path(
    "src/main/java/com/abdul/catalogo/masterdata/service/"
    "MasterDataImportService.java"
)


def backend_root() -> Path:
    candidates = (ROOT / "backend-app-catalogo", ROOT)
    for candidate in candidates:
        if (candidate / RELATIVE).exists():
            return candidate
    raise SystemExit(
        "No se encontró MasterDataImportService.java. Ejecuta este script "
        "desde la raíz del repositorio combinado o desde backend-app-catalogo."
    )


BACKEND = backend_root()
TARGET = BACKEND / RELATIVE
BACKUP_ROOT = BACKEND / ".correction_backups" / datetime.now().strftime("%Y%m%d_%H%M%S")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"Modificado: {path.relative_to(BACKEND)}")


def backup(path: Path) -> None:
    target = BACKUP_ROOT / path.relative_to(BACKEND)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)


def replace_once(content: str, old: str, new: str, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"No se pudo aplicar '{label}'. "
            f"Se esperaba 1 coincidencia y se encontraron {count}."
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


def main() -> None:
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

    old_parse = """            parseAttributeUnits(workbook, context, result);
            parsePriceLists(workbook, context, result);
            if (result.isEmpty()) {
"""
    new_parse = """            parseAttributeUnits(workbook, context, result);
            parsePriceLists(workbook, context, result);
            if (result.size() > MAX_ROWS) {
                throw new BusinessRuleException(
                        "MASTER_IMPORT_ROW_LIMIT",
                        "El libro supera el máximo de " + MAX_ROWS + " filas de datos maestros.");
            }
            if (result.isEmpty()) {
"""
    content = replace_unless_applied(
        content,
        old_parse,
        new_parse,
        '"MASTER_IMPORT_ROW_LIMIT"',
        "limitar filas de datos maestros",
    )

    old_units = """            decimal(payload, "factor_a_base", row.value("factorbase"), "1", errors, "FactorBase");
            integer(payload, "decimales", row.value("decimales"), 3, errors, "Decimales");
            payload.put("estado", active(row.value("estado")));
"""
    new_units = """            decimal(payload, "factor_a_base", row.value("factorbase"), "1", errors, "FactorBase");
            integer(payload, "decimales", row.value("decimales"), 3, errors, "Decimales");
            if (payload.path("factor_a_base").decimalValue().signum() <= 0) {
                errors.add("FactorBase debe ser mayor que cero.");
            }
            if (payload.path("decimales").asInt() < 0) {
                errors.add("Decimales no puede ser negativo.");
            }
            payload.put("estado", active(row.value("estado"), errors));
"""
    content = replace_unless_applied(
        content,
        old_units,
        new_units,
        'errors.add("FactorBase debe ser mayor que cero.")',
        "validar FactorBase y Decimales",
    )

    old_attributes = """            payload.put("clave", defaultValue(row.value("clave"), safeToken(name)));
            payload.put("tipo_dato", type); payload.put("nivel_captura", defaultValue(row.value("nivelcaptura"), "familia"));
            payload.put("requerido_activar", yes(row.value("requerido"))); payload.put("visible_ficha", !no(row.value("visibleficha")));
"""
    new_attributes = """            payload.put("clave", defaultValue(row.value("clave"), safeToken(name)));
            String captureLevel = defaultValue(row.value("nivelcaptura"), "familia").toLowerCase(Locale.ROOT);
            if (!CAPTURE_LEVELS.contains(captureLevel)) {
                errors.add("NivelCaptura no es válido: " + captureLevel + ".");
            }
            payload.put("tipo_dato", type); payload.put("nivel_captura", captureLevel);
            payload.put("requerido_activar", yes(row.value("requerido"))); payload.put("visible_ficha", !no(row.value("visibleficha")));
"""
    content = replace_unless_applied(
        content,
        old_attributes,
        new_attributes,
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
        print(f"Actualizados {occurrences} usos de Estado a validación estricta.")
    else:
        print("Ya aplicada: validación estricta de Estado")

    old_active = """    private boolean yes(String value) { return Set.of("si", "sí", "1", "true", "yes", "activo").contains(masters.normalize(value)); }
    private boolean no(String value) { return Set.of("no", "0", "false", "inactivo").contains(masters.normalize(value)); }
    private boolean active(String value) { return value == null || value.isBlank() || !no(value); }
"""
    new_active = """    private boolean yes(String value) { return Set.of("si", "sí", "1", "true", "yes", "activo").contains(masters.normalize(value)); }
    private boolean no(String value) { return Set.of("no", "0", "false", "inactivo").contains(masters.normalize(value)); }
    private boolean active(String value, List<String> errors) {
        if (value == null || value.isBlank()) return true;
        if (yes(value)) return true;
        if (no(value)) return false;
        errors.add("Estado debe ser SI/NO, ACTIVO/INACTIVO, 1/0 o TRUE/FALSE.");
        return true;
    }
"""
    content = replace_unless_applied(
        content,
        old_active,
        new_active,
        "private boolean active(String value, List<String> errors)",
        "rechazar estados desconocidos",
    )

    old_help = """            help(instructions, "Jerarquía", "RutaPadre y RutaCategoria usan >, por ejemplo Línea Moto > Pernos para moto.");
            help(instructions, "Relación de marca", "Relaciona la marca con la categoría principal. Las subcategorías heredan esa relación.");
"""
    new_help = """            help(instructions, "Jerarquía", "RutaPadre y RutaCategoria usan >, por ejemplo Línea Moto > Pernos para moto.");
            help(instructions, "Orden de categorías", "En Categorias, coloca cada fila padre antes de sus hijas.");
            help(instructions, "Valores permitidos", "Estado: SI/NO o ACTIVO/INACTIVO. TipoDato: texto_corto, numero, numero_unidad, lista_unica, lista_multiple o si_no. NivelCaptura: familia, variante o decidir.");
            help(instructions, "Relación de marca", "Relaciona la marca con la categoría principal. Las subcategorías heredan esa relación.");
"""
    content = replace_unless_applied(
        content,
        old_help,
        new_help,
        '"Orden de categorías"',
        "documentar orden y enums en la plantilla",
    )

    backup(TARGET)
    write(TARGET, content)

    print(f"\nCopia de seguridad: {BACKUP_ROOT.relative_to(BACKEND)}")
    print("Guardas de maestros aplicadas correctamente.")
    print("\nEjecuta ahora, desde backend-app-catalogo:")
    print("  mvnw.cmd test")
    print("\nLuego vuelve a descargar la plantilla de maestros desde el backend.")
    print("No reutilices una plantilla descargada antes de aplicar esta corrección.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"\nERROR: {exception}", file=sys.stderr)
        raise
