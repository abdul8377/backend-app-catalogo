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
MIGRATION_RELATIVE = Path(
    "src/main/resources/db/migration/"
    "V13__align_master_import_row_number.sql"
)


def backend_root() -> Path:
    for candidate in (ROOT / "backend-app-catalogo", ROOT):
        if (candidate / SERVICE_RELATIVE).exists() and (candidate / "pom.xml").exists():
            return candidate
    raise SystemExit(
        "No se encontró el backend. Ejecuta este script desde "
        "D:\\backend-app-catalogo o desde la raíz del repositorio combinado."
    )


BACKEND = backend_root()
SERVICE = BACKEND / SERVICE_RELATIVE
MIGRATION = BACKEND / MIGRATION_RELATIVE
BACKUP_ROOT = (
    BACKEND / ".correction_backups" / datetime.now().strftime("%Y%m%d_%H%M%S")
)


def backup(path: Path) -> None:
    if not path.exists():
        return
    destination = BACKUP_ROOT / path.relative_to(BACKEND)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)


def main() -> None:
    content = SERVICE.read_text(encoding="utf-8")

    # Cambia solo los identificadores SQL; los nombres Java rowNumber se conservan.
    occurrences = content.count("row_number")
    if occurrences:
        backup(SERVICE)
        content = content.replace("row_number", "num_row")
        SERVICE.write_text(content, encoding="utf-8", newline="\n")
        print(
            f"Corregido: {SERVICE.relative_to(BACKEND)} "
            f"({occurrences} referencias SQL)"
        )
    else:
        if "num_row" not in content:
            raise SystemExit(
                "No se encontró row_number ni num_row en MasterDataImportService.java."
            )
        print("MasterDataImportService.java ya utiliza num_row.")

    if MIGRATION.exists():
        backup(MIGRATION)
        MIGRATION.unlink()
        print(f"Eliminada migración inválida: {MIGRATION.relative_to(BACKEND)}")
    else:
        print("La migración V13 inválida ya no existe.")

    print(f"\nCopias de seguridad: {BACKUP_ROOT.relative_to(BACKEND)}")
    print("\nPaso siguiente:")
    print("  1. Ejecuta el SQL reparar_flyway_v13_mysql.sql en MySQL Workbench.")
    print("  2. Ejecuta .\\mvnw.cmd clean test")
    print("  3. Inicia nuevamente el backend.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"\nERROR: {exception}", file=sys.stderr)
        raise
