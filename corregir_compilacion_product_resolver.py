from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil
import sys

ROOT = Path.cwd()
RELATIVE = Path(
    "src/main/java/com/abdul/catalogo/product/importing/service/"
    "ProductMasterDataResolver.java"
)


def backend_root() -> Path:
    for candidate in (ROOT / "backend-app-catalogo", ROOT):
        if (candidate / RELATIVE).exists() and (candidate / "pom.xml").exists():
            return candidate
    raise SystemExit(
        "No se encontró el backend. Ejecuta este script desde "
        "D:\\backend-app-catalogo o desde la raíz del repositorio combinado."
    )


BACKEND = backend_root()
TARGET = BACKEND / RELATIVE
BACKUP_ROOT = (
    BACKEND / ".correction_backups" / datetime.now().strftime("%Y%m%d_%H%M%S")
)

OLD = "rawAttributes.propertyNames().forEachRemaining(rawName -> {"
NEW = "rawAttributes.propertyNames().forEach(rawName -> {"


def main() -> None:
    content = TARGET.read_text(encoding="utf-8")

    if NEW in content and OLD not in content:
        print("La corrección ya estaba aplicada.")
        return

    count = content.count(OLD)
    if count != 1:
        raise SystemExit(
            "No se pudo aplicar la corrección. "
            f"Se esperaba 1 coincidencia y se encontraron {count}."
        )

    backup = BACKUP_ROOT / TARGET.relative_to(BACKEND)
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(TARGET, backup)

    TARGET.write_text(
        content.replace(OLD, NEW, 1),
        encoding="utf-8",
        newline="\n",
    )

    print(f"Corregido: {TARGET.relative_to(BACKEND)}")
    print(f"Copia de seguridad: {backup.relative_to(BACKEND)}")
    print("\nEjecuta ahora:")
    print("  .\\mvnw.cmd clean test")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"\nERROR: {exception}", file=sys.stderr)
        raise
