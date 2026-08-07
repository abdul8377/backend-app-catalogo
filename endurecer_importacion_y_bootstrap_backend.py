from __future__ import annotations

from datetime import datetime
from pathlib import Path
import re
import shutil
import sys


EXECUTOR = Path(
    "src/main/java/com/abdul/catalogo/product/importing/service/"
    "ProductImportExecutor.java"
)
RESOLVER = Path(
    "src/main/java/com/abdul/catalogo/product/importing/service/"
    "ProductMasterDataResolver.java"
)
BOOTSTRAP = Path(
    "src/main/java/com/abdul/catalogo/synchronization/repository/"
    "BootstrapSnapshotRepository.java"
)
IMPORT_SERVICE = Path(
    "src/main/java/com/abdul/catalogo/product/importing/service/"
    "ProductImportService.java"
)

NEW_BIND_METHOD = '''    private void bindNestedProductId(ObjectNode aggregate, String productId) {
        for (JsonNode raw : aggregate.path("attributeValues")) {
            if (!(raw instanceof ObjectNode value)) continue;
            String variantId = value.path("variante_id").asText("").trim();
            if (variantId.isBlank()) {
                value.put("producto_id", productId);
                value.putNull("variante_id");
            } else {
                value.remove("producto_id");
            }
        }
        for (JsonNode raw : aggregate.path("familyAxes")) {
            if (raw instanceof ObjectNode axis) axis.put("producto_id", productId);
        }
        for (JsonNode raw : aggregate.path("attributeOptions")) {
            if (raw instanceof ObjectNode option) option.remove("producto_id");
        }
    }
'''

BOOTSTRAP_PATTERN = re.compile(
    r'\+\s*"ORDER\s+BY\s+"\s*'
    r'\+\s*DEPENDENCY_ORDER\s*'
    r'\+\s*",\s*entity_id\s+LIMIT\s+\?\s+OFFSET\s+\?\s*",',
    re.IGNORECASE | re.MULTILINE,
)
BOOTSTRAP_REPLACEMENT = '''+ "ORDER BY " + DEPENDENCY_ORDER + ", "
                        + "CASE WHEN entity_type = 'CATEGORY' THEN "
                        + "CASE WHEN EXISTS (SELECT 1 FROM categorias category_row "
                        + "WHERE category_row.id = snapshot_records.entity_id "
                        + "AND category_row.categoria_padre_id IS NULL) "
                        + "THEN 0 ELSE 1 END ELSE 0 END, "
                        + "entity_id LIMIT ? OFFSET ?",'''


def backend_root(start: Path) -> Path:
    for candidate in (start, start / "backend-app-catalogo"):
        if (
            (candidate / "pom.xml").exists()
            and (candidate / EXECUTOR).exists()
        ):
            return candidate
    raise SystemExit(
        "No se encontro backend-app-catalogo. Ejecuta el script desde "
        "la raiz que contiene pom.xml."
    )


def backup_and_write(
    root: Path,
    relative: Path,
    updated: str,
    stamp: str,
) -> None:
    target = root / relative
    backup = root / ".correction_backups" / stamp / relative
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup)
    target.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Modificado: {relative}")


def replace_bind_method(content: str) -> str:
    start_marker = "    private void bindNestedProductId("
    end_marker = "    private ObjectNode readObject("
    start = content.find(start_marker)
    end = content.find(end_marker, start + 1)
    if start < 0 or end < 0 or end <= start:
        raise SystemExit(
            "No se localizaron los limites de bindNestedProductId."
        )
    current = content[start:end]
    if 'option.remove("producto_id")' in current:
        return content
    return content[:start] + NEW_BIND_METHOD + content[end:]


def patch_executor(root: Path, stamp: str) -> None:
    path = root / EXECUTOR
    content = path.read_text(encoding="utf-8")
    updated = replace_bind_method(content)
    if updated == content:
        print("Ya corregido: ProductImportExecutor.")
        return
    backup_and_write(root, EXECUTOR, updated, stamp)


def patch_resolver(root: Path, stamp: str) -> None:
    path = root / RESOLVER
    content = path.read_text(encoding="utf-8")
    marker = 'selectedOption.put("categoria_atributo_id", definition.id());'
    if marker in content:
        print("Ya corregido: opciones con categoria_atributo_id.")
        return

    old = '''            selectedOption.put("producto_atributo_id", valueId);
            selectedOption.put("opcion_id", option.id());
'''
    new = '''            selectedOption.put("producto_atributo_id", valueId);
            selectedOption.put("categoria_atributo_id", definition.id());
            selectedOption.put("opcion_id", option.id());
'''
    if content.count(old) != 1:
        raise SystemExit(
            "No se localizo el bloque selectedOption esperado."
        )
    backup_and_write(root, RESOLVER, content.replace(old, new, 1), stamp)


def patch_bootstrap(root: Path, stamp: str) -> None:
    path = root / BOOTSTRAP
    content = path.read_text(encoding="utf-8")
    if "category_row.categoria_padre_id IS NULL" in content:
        print("Ya corregido: categorias raiz antes que hijas.")
        return

    updated, count = BOOTSTRAP_PATTERN.subn(
        BOOTSTRAP_REPLACEMENT,
        content,
        count=1,
    )
    if count != 1:
        raise SystemExit(
            "No se localizo el ORDER BY del bootstrap."
        )
    backup_and_write(root, BOOTSTRAP, updated, stamp)


def patch_preview_revision(root: Path, stamp: str) -> None:
    path = root / IMPORT_SERVICE
    original = path.read_text(encoding="utf-8")
    content = original

    if 'IMPORT_CONTRACT_REVISION = "projection-v2"' not in content:
        class_marker = '    public static final String TEMPLATE_VERSION = "2.1";'
        if content.count(class_marker) != 1:
            raise SystemExit("No se localizo TEMPLATE_VERSION.")
        content = content.replace(
            class_marker,
            class_marker
            + '\n    private static final String '
            + 'IMPORT_CONTRACT_REVISION = "projection-v2";',
            1,
        )

    old_call = "String hash = combinedHash(bytes, zipBytes);"
    new_call = (
        "String hash = combinedHash(bytes, zipBytes, "
        "TEMPLATE_VERSION + \":\" + IMPORT_CONTRACT_REVISION);"
    )
    if old_call in content:
        content = content.replace(old_call, new_call, 1)

    old_method = '''    private String combinedHash(byte[] workbook, byte[] zip) {
        byte[] separator = "::IMAGES::".getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[workbook.length + separator.length + zip.length];
        System.arraycopy(workbook, 0, combined, 0, workbook.length);
        System.arraycopy(separator, 0, combined, workbook.length, separator.length);
        System.arraycopy(zip, 0, combined, workbook.length + separator.length, zip.length);
        return Digests.sha256(combined);
    }
'''
    new_method = '''    private String combinedHash(byte[] workbook, byte[] zip, String contractRevision) {
        byte[] separator = "::IMAGES::".getBytes(StandardCharsets.UTF_8);
        byte[] revision = ("::CONTRACT::" + contractRevision).getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[
                workbook.length + separator.length + zip.length + revision.length];
        System.arraycopy(workbook, 0, combined, 0, workbook.length);
        System.arraycopy(separator, 0, combined, workbook.length, separator.length);
        System.arraycopy(zip, 0, combined, workbook.length + separator.length, zip.length);
        System.arraycopy(revision, 0, combined,
                workbook.length + separator.length + zip.length, revision.length);
        return Digests.sha256(combined);
    }
'''
    if old_method in content:
        content = content.replace(old_method, new_method, 1)

    required = (
        'IMPORT_CONTRACT_REVISION = "projection-v2"',
        "combinedHash(bytes, zipBytes,",
        '"::CONTRACT::" + contractRevision',
    )
    if not all(fragment in content for fragment in required):
        raise SystemExit(
            "No se pudo actualizar de forma segura la revision de preview."
        )

    if content == original:
        print("Ya corregido: hash de preview versionado.")
        return
    backup_and_write(root, IMPORT_SERVICE, content, stamp)


def main() -> None:
    root = backend_root(Path.cwd())
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    print(f"Backend detectado: {root}")

    patch_executor(root, stamp)
    patch_resolver(root, stamp)
    patch_bootstrap(root, stamp)
    patch_preview_revision(root, stamp)

    print()
    print("Correcciones backend aplicadas.")
    print("Ejecuta:")
    print(r"  .\mvnw.cmd test")
    print(r"  .\mvnw.cmd clean package -DskipTests")
    print(r"  .\mvnw.cmd spring-boot:run")
    print()
    print("Vuelve a crear la vista previa de importacion.")
    print("El mismo XLSX/ZIP generara un hash nuevo por projection-v2.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
