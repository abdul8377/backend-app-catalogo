from __future__ import annotations

from datetime import datetime
from pathlib import Path
import argparse
import shutil
import sys

ROOT = Path.cwd()
RESOLVER = Path(
    'src/main/java/com/abdul/catalogo/product/importing/service/ProductMasterDataResolver.java'
)
PROJECTION = Path(
    'src/main/java/com/abdul/catalogo/product/service/ProductProjectionService.java'
)


def find_root() -> Path:
    for candidate in (ROOT, ROOT / 'backend-app-catalogo'):
        if (candidate / 'pom.xml').exists() and (candidate / RESOLVER).exists():
            return candidate
    raise SystemExit('No se encontro backend-app-catalogo.')


BACKEND = find_root()
STAMP = datetime.now().strftime('%Y%m%d_%H%M%S')
BACKUP = BACKEND / '.correction_backups' / STAMP


def read(rel: Path) -> str:
    return (BACKEND / rel).read_text(encoding='utf-8')


def write(rel: Path, content: str, check: bool) -> None:
    if check:
        print(f'CHECK OK: {rel}')
        return
    target = BACKEND / rel
    backup = BACKUP / rel
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup)
    target.write_text(content, encoding='utf-8', newline='\n')
    print(f'Modificado: {rel}')


def patch_resolver(check: bool) -> None:
    content = read(RESOLVER)
    if 'Un producto MATRIX debe tener al menos dos atributos de variante' in content:
        print('Ya corregido: ProductMasterDataResolver')
        return
    old = '''        if (order == 0) {
            errors.add("Un producto MATRIX debe tener al menos un atributo de variante "
                    + "marcado PuedeSerEje con valores distintos.");
        }
'''
    new = '''        if (order < 2) {
            errors.add("Un producto MATRIX debe tener al menos dos atributos de variante "
                    + "independientes, marcados PuedeSerEje y con valores distintos.");
        }
'''
    if content.count(old) != 1:
        raise SystemExit('No se encontro el bloque inferAxes esperado.')
    write(RESOLVER, content.replace(old, new, 1), check)


def patch_projection(check: bool) -> None:
    content = read(PROJECTION)
    marker = 'MATRIX_PRODUCT_AXES_REQUIRED'
    if marker in content:
        print('Ya corregido: ProductProjectionService')
        return
    old = '''        requireOptionalArray(aggregate, "familyAxes");
        requireOptionalArray(aggregate, "attributeValues");
'''
    new = '''        requireOptionalArray(aggregate, "familyAxes");
        if (productType(aggregate) == ProductType.MATRIX
                && aggregate.path("familyAxes").size() < 2) {
            throw new BusinessRuleException(
                    "MATRIX_PRODUCT_AXES_REQUIRED",
                    "Un producto MATRIX debe declarar al menos dos ejes independientes.");
        }
        requireOptionalArray(aggregate, "attributeValues");
'''
    if content.count(old) != 1:
        raise SystemExit('No se encontro el bloque familyAxes esperado.')
    write(PROJECTION, content.replace(old, new, 1), check)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    print(f'Backend detectado: {BACKEND}')
    patch_resolver(args.check)
    patch_projection(args.check)
    if args.check:
        print('CHECK COMPLETO: el backend puede endurecer MATRIX sin escribir archivos.')
        return
    print('Correccion aplicada. Ejecuta .\\mvnw.cmd test antes de publicar.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'ERROR: {error}', file=sys.stderr)
        raise
