from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil
import sys

ROOT = Path.cwd()

BACKEND_CANDIDATES = (
    ROOT / "backend-app-catalogo",
    ROOT,
)

BASE_RELATIVE = Path("src/main/java/com/abdul/catalogo")

RESOLVER_RELATIVE = BASE_RELATIVE / "product/importing/service/ProductMasterDataResolver.java"
PARSER_RELATIVE = BASE_RELATIVE / "product/importing/service/ProductWorkbookParser.java"
PROJECTION_RELATIVE = BASE_RELATIVE / "product/service/ProductProjectionService.java"
VALIDATOR_RELATIVE = BASE_RELATIVE / "product/importing/service/ProductImportValidator.java"


def backend_root() -> Path:
    for candidate in BACKEND_CANDIDATES:
        if (candidate / RESOLVER_RELATIVE).exists():
            return candidate
    raise SystemExit(
        "No se encontró el backend. Ejecuta este script desde la raíz del "
        "repositorio combinado o desde backend-app-catalogo."
    )


BACKEND = backend_root()
RESOLVER = BACKEND / RESOLVER_RELATIVE
PARSER = BACKEND / PARSER_RELATIVE
PROJECTION = BACKEND / PROJECTION_RELATIVE
VALIDATOR = BACKEND / VALIDATOR_RELATIVE

BACKUP_ROOT = BACKEND / ".correction_backups" / datetime.now().strftime("%Y%m%d_%H%M%S")


def read(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"No se encontró el archivo: {path}")
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


def patch_resolver() -> None:
    content = read(RESOLVER)

    content = replace_unless_applied(
        content,
        """import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
""",
        """import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
""",
        "import java.util.LinkedHashMap;",
        "agregar imports para resolver IDs de listas",
    )

    old = """        JsonNode rawPricing = aggregate.path("pricingConfiguration");
        if (rawPricing instanceof ObjectNode pricing && pricing.path("price_lists") instanceof ArrayNode lists) {
            for (JsonNode raw : lists) {
                if (!(raw instanceof ObjectNode list)) continue;
                String name = list.path("name").asText("General").trim();
                String id = masters.findPriceListId(name).orElse("");
                if (id.isBlank()) {
                    errors.add("No existe la lista de precios " + name + ".");
                } else {
                    list.put("id", id);
                }
                list.put("currency", "PEN");
            }
        }
"""

    new = """        JsonNode rawPricing = aggregate.path("pricingConfiguration");
        if (rawPricing instanceof ObjectNode pricing && pricing.path("lists") instanceof ArrayNode lists) {
            Map<String, String> resolvedListIds = new LinkedHashMap<>();
            for (JsonNode raw : lists) {
                if (!(raw instanceof ObjectNode list)) continue;
                String previousId = list.path("id").asText("").trim();
                String name = list.path("name").asText("General").trim();
                String id = masters.findPriceListId(name).orElse("");
                if (id.isBlank()) {
                    errors.add("No existe la lista de precios " + name + ".");
                } else {
                    list.put("id", id);
                    if (!previousId.isBlank()) resolvedListIds.put(previousId, id);
                }
                list.put("currency_code", "PEN");
                list.put("currency", "PEN");
            }
            JsonNode rawConfiguredPrices = pricing.path("prices");
            if (rawConfiguredPrices instanceof ArrayNode configuredPrices) {
                for (JsonNode raw : configuredPrices) {
                    if (!(raw instanceof ObjectNode configuredPrice)) continue;
                    String previousListId = configuredPrice.path("list_id").asText("").trim();
                    String resolvedListId = resolvedListIds.get(previousListId);
                    if (resolvedListId != null) configuredPrice.put("list_id", resolvedListId);
                }
            }
        }
"""

    content = replace_unless_applied(
        content,
        old,
        new,
        'pricing.path("lists") instanceof ArrayNode lists',
        "resolver pricingConfiguration.lists y propagar IDs reales",
    )

    backup(RESOLVER)
    write(RESOLVER, content)


def patch_parser() -> None:
    content = read(PARSER)

    old_family = """        for (RowData product : products) {
            String family = family(product);
            if (!family.isBlank()) productByFamily.putIfAbsent(family, product);
        }
"""
    new_family = """        for (RowData product : products) {
            String family = family(product);
            if (family.isBlank()) continue;
            RowData previous = productByFamily.putIfAbsent(family, product);
            if (previous != null) {
                throw new BusinessRuleException(
                        "DUPLICATE_PRODUCT_FAMILY",
                        "El código de familia " + family + " está repetido en Productos, filas "
                                + previous.rowNumber() + " y " + product.rowNumber() + ".");
            }
        }
"""
    content = replace_unless_applied(
        content,
        old_family,
        new_family,
        '"DUPLICATE_PRODUCT_FAMILY"',
        "rechazar CodigoFamilia repetido",
    )

    old_integer = """    private Long integer(String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value.trim().replace(".0", ""));
        } catch (NumberFormatException exception) {
            errors.add(label + " debe ser un entero.");
            return null;
        }
    }
"""
    new_integer = """    private Long integer(String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim().replace(',', '.')).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            errors.add(label + " debe ser un entero.");
            return null;
        }
    }
"""
    content = replace_unless_applied(
        content,
        old_integer,
        new_integer,
        "longValueExact()",
        "evitar que 10.01 se convierta silenciosamente en 101",
    )

    backup(PARSER)
    write(PARSER, content)


def patch_projection() -> None:
    content = read(PROJECTION)

    content = replace_unless_applied(
        content,
        '        validateImages(aggregate.path("images"));\n',
        '        validateImages(aggregate.path("images"), variantSummary.allSkus());\n',
        'validateImages(aggregate.path("images"), variantSummary.allSkus())',
        "validar referencias SKU de imágenes",
    )

    old_status = """            String variantStatus = firstText(variant, "status", "estado").toUpperCase(Locale.ROOT);
            if (variantStatus.isBlank() || variantStatus.equals("ACTIVE") || variantStatus.equals("1") || variantStatus.equals("TRUE")) {
                active.add(sku);
            }
"""
    new_status = """            String variantStatus = firstText(variant, "status", "estado").toUpperCase(Locale.ROOT);
            if (!Set.of("", "ACTIVE", "INACTIVE", "1", "0", "TRUE", "FALSE").contains(variantStatus)) {
                throw new BusinessRuleException(
                        "INVALID_VARIANT_STATUS",
                        "El estado de la variante " + sku + " no es válido: " + variantStatus + ".");
            }
            if (variantStatus.isBlank() || variantStatus.equals("ACTIVE")
                    || variantStatus.equals("1") || variantStatus.equals("TRUE")) {
                active.add(sku);
            }
"""
    content = replace_unless_applied(
        content,
        old_status,
        new_status,
        '"INVALID_VARIANT_STATUS"',
        "rechazar estados de variante desconocidos",
    )

    old_images = """    private void validateImages(JsonNode images) {
        int primary = 0;
        for (JsonNode image : images) {
            String storageKey = firstText(image, "storageKey", "storage_key").replace('\\\\', '/');
"""
    new_images = """    private void validateImages(JsonNode images, Set<String> allSkus) {
        int primary = 0;
        for (JsonNode image : images) {
            String sku = text(image, "sku").toUpperCase(Locale.ROOT);
            if (!sku.isBlank() && !allSkus.contains(sku)) {
                throw new BusinessRuleException(
                        "INVALID_PRODUCT_REFERENCE",
                        "Una imagen referencia el SKU inexistente " + sku + ".");
            }
            String storageKey = firstText(image, "storageKey", "storage_key").replace('\\\\', '/');
"""
    content = replace_unless_applied(
        content,
        old_images,
        new_images,
        "private void validateImages(JsonNode images, Set<String> allSkus)",
        "rechazar SKU inexistente en Imagenes",
    )

    backup(PROJECTION)
    write(PROJECTION, content)


def patch_attribute_safety_guard() -> None:
    """
    Protección temporal: el Excel ya acepta Atributos, pero el parser actual
    no construye attributeValues/attributeOptions normalizados. Esta guarda
    evita confirmar productos aparentemente correctos que perderían esas
    proyecciones relacionales.
    """
    content = read(VALIDATOR)

    old_insert = """        ObjectNode aggregate = candidate.aggregate().deepCopy();
        String validationId = productId == null ? UUID.randomUUID().toString() : productId;
"""
    new_insert = """        ObjectNode aggregate = candidate.aggregate().deepCopy();
        if (hasUnresolvedImportedAttributes(aggregate)) {
            messages.add(
                    "La fila contiene atributos del Excel, pero todavía no fueron resueltos "
                            + "contra categoria_atributos ni proyectados en attributeValues. "
                            + "No confirmes esta fila hasta implementar el resolvedor de atributos.");
        }
        String validationId = productId == null ? UUID.randomUUID().toString() : productId;
"""
    content = replace_unless_applied(
        content,
        old_insert,
        new_insert,
        "hasUnresolvedImportedAttributes(aggregate)",
        "bloquear pérdida silenciosa de atributos importados",
    )

    old_end = """    public record ValidationResult(ProductImportAction action, ProductImportRowStatus status,
                                   String productId, Long expectedVersion, List<String> messages) {
    }
}
"""
    new_end = """    private boolean hasUnresolvedImportedAttributes(ObjectNode aggregate) {
        boolean commonAttributes = aggregate.path("attributes").isObject()
                && !aggregate.path("attributes").isEmpty();
        boolean variantAttributes = false;
        for (var variant : aggregate.path("variants")) {
            if (variant.path("attributes").isObject() && !variant.path("attributes").isEmpty()) {
                variantAttributes = true;
                break;
            }
        }
        return (commonAttributes || variantAttributes)
                && aggregate.path("attributeValues").isArray()
                && aggregate.path("attributeValues").isEmpty();
    }

    public record ValidationResult(ProductImportAction action, ProductImportRowStatus status,
                                   String productId, Long expectedVersion, List<String> messages) {
    }
}
"""
    content = replace_unless_applied(
        content,
        old_end,
        new_end,
        "private boolean hasUnresolvedImportedAttributes",
        "agregar detector de atributos no normalizados",
    )

    backup(VALIDATOR)
    write(VALIDATOR, content)


def main() -> None:
    print(f"Backend detectado: {BACKEND}")
    print(f"Copias de seguridad: {BACKUP_ROOT.relative_to(BACKEND)}")
    patch_resolver()
    patch_parser()
    patch_projection()
    patch_attribute_safety_guard()
    print("\nCorrecciones críticas y guarda temporal aplicadas.")
    print("\nEjecuta ahora, desde backend-app-catalogo:")
    print("  mvnw.cmd test")
    print("o en PowerShell:")
    print("  .\\mvnw.cmd test")
    print("\nDespués descarga una plantilla nueva desde:")
    print("  /admin/products/import/template")
    print("\nIMPORTANTE:")
    print("  La guarda temporal rechazará productos con atributos hasta que exista")
    print("  el resolvedor completo Atributos Excel -> categoria_atributos ->")
    print("  attributeValues/attributeOptions. No la elimines para una carga real.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"\nERROR: {exception}", file=sys.stderr)
        raise
