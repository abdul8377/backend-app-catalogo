from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil
import sys


ROOT = Path.cwd()
RELATIVE_FILE = Path(
    "src/main/java/com/abdul/catalogo/synchronization/"
    "repository/BootstrapSnapshotRepository.java"
)

OLD_BLOCK = '''        List<SnapshotRecord> records = jdbcTemplate.query(SNAPSHOT_CTE
                + "SELECT entity_type, entity_id, version, deleted, payload_json, updated_at "
                + "FROM snapshot_records WHERE entity_type IN (" + SUPPORTED_TYPES + ") "
                + "ORDER BY " + DEPENDENCY_ORDER + ", entity_id LIMIT ? OFFSET ?",
'''

NEW_BLOCK = '''        List<SnapshotRecord> records = jdbcTemplate.query(SNAPSHOT_CTE
                + "SELECT snapshot.entity_type, snapshot.entity_id, snapshot.version, snapshot.deleted, "
                + "snapshot.payload_json, snapshot.updated_at "
                + "FROM snapshot_records snapshot "
                + "LEFT JOIN categorias category_row "
                + "ON snapshot.entity_type = 'CATEGORY' AND category_row.id = snapshot.entity_id "
                + "WHERE snapshot.entity_type IN (" + SUPPORTED_TYPES + ") "
                + "ORDER BY " + DEPENDENCY_ORDER + ", "
                + "CASE WHEN snapshot.entity_type = 'CATEGORY' "
                + "THEN CASE WHEN category_row.categoria_padre_id IS NULL THEN 0 ELSE 1 END "
                + "ELSE 0 END, snapshot.entity_id LIMIT ? OFFSET ?",
'''

MARKERS = (
    "LEFT JOIN categorias category_row",
    "category_row.categoria_padre_id IS NULL",
    "snapshot.entity_id LIMIT ? OFFSET ?",
)


def find_backend_root() -> Path:
    candidates = (
        ROOT,
        ROOT / "backend-app-catalogo",
    )
    for candidate in candidates:
        if (
            (candidate / RELATIVE_FILE).exists()
            and (candidate / "pom.xml").exists()
        ):
            return candidate
    raise SystemExit(
        "No se encontro backend-app-catalogo. Ejecuta este script desde "
        "la raiz del repositorio backend o desde una carpeta que contenga "
        "backend-app-catalogo."
    )


def main() -> None:
    backend = find_backend_root()
    target = backend / RELATIVE_FILE
    content = target.read_text(encoding="utf-8")

    print(f"Backend detectado: {backend}")
    print(f"Archivo: {RELATIVE_FILE}")

    if all(marker in content for marker in MARKERS):
        print("La correccion de orden de categorias ya esta aplicada.")
        return

    matches = content.count(OLD_BLOCK)
    if matches != 1:
        raise SystemExit(
            "No se pudo localizar el bloque esperado de "
            "BootstrapSnapshotRepository.java. "
            f"Coincidencias encontradas: {matches}. "
            "El archivo puede haber cambiado; no se modifico nada."
        )

    backup_root = (
        backend
        / ".correction_backups"
        / datetime.now().strftime("%Y%m%d_%H%M%S")
    )
    backup = backup_root / RELATIVE_FILE
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup)

    updated = content.replace(OLD_BLOCK, NEW_BLOCK, 1)
    if not all(marker in updated for marker in MARKERS):
        raise SystemExit(
            "La validacion interna del parche fallo; no se guardo el archivo."
        )

    target.write_text(updated, encoding="utf-8", newline="\n")

    print("Correccion aplicada correctamente.")
    print(f"Copia de seguridad: {backup.relative_to(backend)}")
    print()
    print("Ahora ejecuta en el backend:")
    print(r"  .\mvnw.cmd test")
    print(r"  .\mvnw.cmd clean package -DskipTests")
    print()
    print("Luego detiene el backend Java que esta corriendo y arrancalo otra vez:")
    print(r"  .\mvnw.cmd spring-boot:run")
    print()
    print("No necesitas recompilar Flutter para probar esta correccion.")
    print("En la tablet pulsa: Reconstruir datos desde la PC.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
