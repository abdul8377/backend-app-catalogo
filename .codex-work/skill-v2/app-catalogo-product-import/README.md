# App Catálogo - Skill de importación de productos

Este paquete convierte catálogos PDF en Excel v2 y ZIP de imágenes compatibles con el backend de App Catálogo.

La versión 2 exige IDs reales de empresa, marca, categoría y subcategoría. Antes de cada lote se debe descargar `referencias-datos-maestros.xlsx` desde el backend de destino; los nombres solo sirven para revisión humana.

## Validación local

```powershell
python tools\validate_import_package.py --xlsx productos_dina.xlsx --images imagenes_dina.zip --references referencias-datos-maestros.xlsx --report reporte.json
```

El validador comprueba los IDs y nombres, marca–empresa, categoría–subcategoría mediante `CategoriaPadreId`, marca–categoría cuando la hoja relacional existe, unidades, atributos, PEN, IGV 18 %, presentaciones, precios e imágenes. `--masters` queda disponible solo para pruebas heredadas.

El archivo `examples/referencias_datos_maestros_ejemplo.xlsx` es ilustrativo. Para una importación real siempre se usa la descarga del mismo backend y la misma base de datos donde se confirmará el lote.
