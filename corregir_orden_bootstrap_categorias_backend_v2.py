from __future__ import annotations

from datetime import datetime
from pathlib import Path
import argparse
import re
import shutil
import sys


RELATIVE_FILE = Path(
    "src/main/java/com/abdul/catalogo/synchronization/"
    "repository/BootstrapSnapshotRepository.java"
)

# Solo se busca la parte estable de la consulta, sin depender de espacios,
# sangria, saltos de linea ni formato aplicado por el IDE.
ORDER_PATTERN = re.compile(
    r'\+\s*"ORDER\s+BY\s+"\s*'
    r'\+\s*DEPENDENCY_ORDER\s*'
    r'\+\s*",\s*entity_id\s+LIMIT\s+\?\s+OFFSET\s+\?\s*",',
    re.IGNORECASE | re.MULTILINE,
)

ORDER_REPLACEMENT = '''+ "ORDER BY " + DEPENDENCY_ORDER + ", "
                        + "CASE WHEN entity_type = 'CATEGORY' THEN "
                        + "CASE WHEN EXISTS (SELECT 1 FROM categorias category_row "
                        + "WHERE category_row.id = snapshot_records.entity_id "
                        + "AND category_row.categoria_padre_id IS NULL) "
                        + "THEN 0 ELSE 1 END "
                        + "ELSE 0 END, entity_id LIMIT ? OFFSET ?",'''

APPLIED_MARKERS = (
    "category_row.id = snapshot_records.entity_id",
    "category_row.categoria_padre_id IS NULL",
)


def find_backend_root(start: Path) -> Path:
    candidates = [
        start,
        start / "backend-app-catalogo",
    ]
    for candidate in candidates:
        if (
            (candidate / "pom.xml").exists()
            and (candidate / RELATIVE_FILE).exists()
        ):
            return candidate
    raise SystemExit(
        "No se encontro el backend. Ejecuta este script desde la raiz "
        "que contiene pom.xml y la carpeta src."
    )


def normalized_excerpt(text: str, position: int, radius: int = 280) -> str:
    start = max(0, position - radius)
    end = min(len(text), position + radius)
    return text[start:end].replace("\r\n", "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="Comprueba el archivo sin modificarlo.",
    )
    args = parser.parse_args()

    backend = find_backend_root(Path.cwd())
    target = backend / RELATIVE_FILE
    content = target.read_text(encoding="utf-8")

    print(f"Backend detectado: {backend}")
    print(f"Archivo: {target.relative_to(backend)}")

    if all(marker in content for marker in APPLIED_MARKERS):
        print("La correccion ya esta aplicada.")
        return

    matches = list(ORDER_PATTERN.finditer(content))
    if len(matches) != 1:
        print(
            "No se encontro exactamente una consulta de bootstrap "
            f"compatible. Coincidencias: {len(matches)}.",
            file=sys.stderr,
        )

        fallback = re.search(
            r"ORDER\s+BY.{0,240}?LIMIT\s+\?\s+OFFSET\s+\?",
            content,
            re.IGNORECASE | re.DOTALL,
        )
        if fallback:
            print("\nFragmento encontrado en tu archivo:", file=sys.stderr)
            print(
                normalized_excerpt(content, fallback.start()),
                file=sys.stderr,
            )
        else:
            print(
                "\nTampoco se encontro ORDER BY ... LIMIT ? OFFSET ?. "
                "Verifica que sea el repositorio y la rama correctos.",
                file=sys.stderr,
            )
        raise SystemExit(2)

    match = matches[0]
    print("\nExpresion localizada:")
    print(match.group(0))

    if args.check:
        print("\nCHECK OK: el parche puede aplicarse; no se modifico nada.")
        return

    backup_root = (
        backend
        / ".correction_backups"
        / datetime.now().strftime("%Y%m%d_%H%M%S")
    )
    backup = backup_root / RELATIVE_FILE
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup)

    updated, count = ORDER_PATTERN.subn(
        ORDER_REPLACEMENT,
        content,
        count=1,
    )
    if count != 1:
        raise SystemExit(
            "La sustitucion no produjo exactamente un cambio. "
            "No se guardo el archivo."
        )

    if not all(marker in updated for marker in APPLIED_MARKERS):
        raise SystemExit(
            "La validacion posterior fallo. No se guardo el archivo."
        )

    target.write_text(updated, encoding="utf-8", newline="\n")

    print("\nCorreccion aplicada.")
    print(f"Copia de seguridad: {backup.relative_to(backend)}")
    print("\nSiguiente paso:")
    print(r"  .\mvnw.cmd test")
    print(r"  .\mvnw.cmd clean package -DskipTests")
    print("\nReinicia el backend que escucha en el puerto 8081.")
    print("Luego pulsa 'Reconstruir datos desde la PC' en la tablet.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
