from __future__ import annotations

from datetime import datetime
from pathlib import Path
import re
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

REVISION_METHOD = r'''    public long currentMasterRevision() {
        Long revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0)
                FROM sync_change_log
                WHERE entity_type IN (
                    'COMPANY',
                    'BRAND',
                    'CATEGORY',
                    'BRAND_CATEGORY',
                    'MEASUREMENT_UNIT',
                    'CATEGORY_ATTRIBUTE',
                    'CATEGORY_ATTRIBUTE_OPTION',
                    'CATEGORY_ATTRIBUTE_UNIT',
                    'PRICE_LIST'
                )
                """, Long.class);
        return revision == null ? 0L : revision;
    }

'''

NEW_HASH_METHOD = r'''    private String combinedHash(byte[] workbook, byte[] zip, long masterRevision) {
        byte[] separator = "::IMAGES::".getBytes(StandardCharsets.UTF_8);
        byte[] context = (
                "::TEMPLATE::" + TEMPLATE_VERSION
                        + "::MASTER_REVISION::" + masterRevision
                ).getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[
                workbook.length + separator.length + zip.length + context.length];
        System.arraycopy(workbook, 0, combined, 0, workbook.length);
        System.arraycopy(separator, 0, combined, workbook.length, separator.length);
        System.arraycopy(zip, 0, combined, workbook.length + separator.length, zip.length);
        System.arraycopy(context, 0, combined,
                workbook.length + separator.length + zip.length, context.length);
        return Digests.sha256(combined);
    }
'''


def root() -> Path:
    for candidate in (ROOT, ROOT / "backend-app-catalogo"):
        if (candidate / "pom.xml").exists() and (candidate / SERVICE).exists():
            return candidate
    raise SystemExit(
        "No se encontro backend-app-catalogo. Ejecuta este script desde "
        "la raiz que contiene pom.xml."
    )


def backup(base: Path, relative: Path, stamp: str) -> None:
    src = base / relative
    dst = base / ".correction_backups" / stamp / relative
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def patch_resolver(base: Path, stamp: str) -> None:
    path = base / RESOLVER
    content = path.read_text(encoding="utf-8")

    if "public long currentMasterRevision()" in content:
        print("Ya aplicado: currentMasterRevision().")
        return

    marker = "    public ProductImportCandidate resolve(ProductImportCandidate candidate) {"
    index = content.find(marker)
    if index < 0:
        raise SystemExit(
            "No se encontro el metodo resolve() en ProductMasterDataResolver."
        )

    backup(base, RESOLVER, stamp)
    updated = content[:index] + REVISION_METHOD + content[index:]
    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Modificado: {RESOLVER}")


def patch_service(base: Path, stamp: str) -> None:
    path = base / SERVICE
    content = path.read_text(encoding="utf-8")
    original = content

    old_call = "String hash = combinedHash(bytes, zipBytes);"
    new_call = (
        "long masterRevision = masterDataResolver.currentMasterRevision();\n"
        "        String hash = combinedHash(bytes, zipBytes, masterRevision);"
    )

    if new_call not in content:
        if content.count(old_call) != 1:
            raise SystemExit(
                "No se encontro exactamente una llamada combinedHash(bytes, zipBytes). "
                "El archivo puede haber cambiado."
            )
        content = content.replace(old_call, new_call, 1)
        print("Aplicado: hash dependiente de la revision de maestros.")
    else:
        print("Ya aplicado: llamada con masterRevision.")

    if "private String combinedHash(byte[] workbook, byte[] zip, long masterRevision)" not in content:
        pattern = re.compile(
            r"    private String combinedHash\(byte\[\] workbook, byte\[\] zip\) \{\n"
            r".*?"
            r"    \}\n",
            re.DOTALL,
        )
        match = pattern.search(content)
        if not match:
            raise SystemExit(
                "No se encontro el metodo combinedHash(byte[], byte[])."
            )
        content = content[:match.start()] + NEW_HASH_METHOD + "\n" + content[match.end():]
        print("Aplicado: fingerprint con TEMPLATE_VERSION y MASTER_REVISION.")
    else:
        print("Ya aplicado: metodo combinedHash versionado.")

    if content != original:
        backup(base, SERVICE, stamp)
        path.write_text(content, encoding="utf-8", newline="\n")
        print(f"Modificado: {SERVICE}")


def main() -> None:
    base = root()
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    print(f"Backend detectado: {base}")
    print(f"Copias: .correction_backups\\{stamp}")

    patch_resolver(base, stamp)
    patch_service(base, stamp)

    print()
    print("Correccion aplicada.")
    print("Ahora ejecuta:")
    print(r"  .\mvnw.cmd test")
    print(r"  .\mvnw.cmd clean package -DskipTests")
    print()
    print("Reinicia el backend y vuelve a subir el MISMO XLSX + ZIP.")
    print("El hash sera nuevo si los datos maestros cambiaron.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
