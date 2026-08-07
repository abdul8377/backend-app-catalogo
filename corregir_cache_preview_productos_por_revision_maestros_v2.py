from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil
import sys


ROOT = Path.cwd()

SERVICE = Path(
    "src/main/java/com/abdul/catalogo/product/importing/service/"
    "ProductImportService.java"
)
RESOLVER = Path(
    "src/main/java/com/abdul/catalogo/product/importing/service/"
    "ProductMasterDataResolver.java"
)

REVISION_METHOD = '    public long currentMasterRevision() {\n        Long revision = jdbc.queryForObject("""\n                SELECT COALESCE(MAX(sequence), 0)\n                FROM sync_change_log\n                WHERE entity_type IN (\n                    \'COMPANY\',\n                    \'BRAND\',\n                    \'CATEGORY\',\n                    \'BRAND_CATEGORY\',\n                    \'MEASUREMENT_UNIT\',\n                    \'CATEGORY_ATTRIBUTE\',\n                    \'CATEGORY_ATTRIBUTE_OPTION\',\n                    \'CATEGORY_ATTRIBUTE_UNIT\',\n                    \'PRICE_LIST\'\n                )\n                """, Long.class);\n        return revision == null ? 0L : revision;\n    }\n\n'
MARKER = "::MASTER_REVISION::"


def backend_root() -> Path:
    for candidate in (ROOT, ROOT / "backend-app-catalogo"):
        if (
            (candidate / "pom.xml").exists()
            and (candidate / SERVICE).exists()
            and (candidate / RESOLVER).exists()
        ):
            return candidate
    raise SystemExit(
        "No se encontro backend-app-catalogo. Ejecuta este script "
        "desde la raiz que contiene pom.xml."
    )


def backup(base: Path, relative: Path, stamp: str) -> None:
    src = base / relative
    dst = base / ".correction_backups" / stamp / relative
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def ensure_revision_method(base: Path, stamp: str) -> None:
    path = base / RESOLVER
    content = path.read_text(encoding="utf-8")

    if "public long currentMasterRevision()" in content:
        print("OK: currentMasterRevision() ya existe.")
        return

    marker = (
        "    public ProductImportCandidate resolve("
        "ProductImportCandidate candidate) {"
    )
    pos = content.find(marker)
    if pos < 0:
        raise SystemExit(
            "No se encontro resolve(ProductImportCandidate) "
            "en ProductMasterDataResolver.java."
        )

    updated = content[:pos] + REVISION_METHOD + content[pos:]
    backup(base, RESOLVER, stamp)
    path.write_text(updated, encoding="utf-8", newline="\n")
    print("Aplicado: currentMasterRevision().")


def patch_hash(base: Path, stamp: str) -> None:
    path = base / SERVICE
    content = path.read_text(encoding="utf-8")

    if MARKER in content and "masterDataResolver.currentMasterRevision()" in content:
        print("OK: el hash ya depende de la revision de maestros.")
        return

    start_token = "String hash = combinedHash("
    start = content.find(start_token)
    if start < 0:
        candidates = [
            line.strip()
            for line in content.splitlines()
            if "hash" in line.lower() and "combinedHash" in line
        ]
        print("No se encontro 'String hash = combinedHash(...)'.")
        if candidates:
            print("Se encontraron estas expresiones relacionadas:")
            for candidate in candidates:
                print("  " + candidate)
        raise SystemExit(
            "ProductImportService.java tiene una forma no prevista. "
            "No se modifico el archivo."
        )

    semicolon = content.find(";", start)
    if semicolon < 0:
        raise SystemExit(
            "Se encontro el inicio de combinedHash pero no su punto y coma."
        )

    statement = content[start:semicolon + 1]
    prefix = "String hash = "
    expression = statement[len(prefix):-1].strip()

    if not expression.startswith("combinedHash("):
        raise SystemExit(
            "La expresion de hash encontrada no tiene la forma esperada."
        )

    replacement = (
        "long masterRevision = masterDataResolver.currentMasterRevision();\n"
        "        String baseHash = " + expression + ";\n"
        "        String hash = Digests.sha256((baseHash + "
        "\"::MASTER_REVISION::\" + masterRevision)"
        ".getBytes(StandardCharsets.UTF_8));"
    )

    print("Expresion actual detectada:")
    print("  " + statement.replace("\n", " ").strip())
    print()
    print("Se conservara esa expresion y se anadira MASTER_REVISION.")

    updated = content[:start] + replacement + content[semicolon + 1:]

    required = (
        "long masterRevision = masterDataResolver.currentMasterRevision();",
        "String baseHash = combinedHash(",
        '"::MASTER_REVISION::" + masterRevision',
    )
    if not all(fragment in updated for fragment in required):
        raise SystemExit(
            "La validacion interna del parche fallo. No se guardo el archivo."
        )

    backup(base, SERVICE, stamp)
    path.write_text(updated, encoding="utf-8", newline="\n")
    print("Aplicado: cache de preview dependiente de datos maestros.")


def main() -> None:
    base = backend_root()
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    print(f"Backend detectado: {base}")
    print(f"Copias: .correction_backups\\{stamp}")

    ensure_revision_method(base, stamp)
    patch_hash(base, stamp)

    print()
    print("CORRECCION COMPLETADA.")
    print()
    print("Ahora ejecuta:")
    print(r"  .\mvnw.cmd test")
    print(r"  .\mvnw.cmd clean package -DskipTests")
    print()
    print("Luego reinicia el backend:")
    print(r"  .\mvnw.cmd spring-boot:run")
    print()
    print("Finalmente vuelve a subir el MISMO XLSX + ZIP.")
    print("No necesitas renombrarlos ni modificar la plantilla.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
