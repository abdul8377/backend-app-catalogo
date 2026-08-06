# Ejecución de ejemplo - DINA v2

## Prompt para el agente

Procesa la página indicada del catálogo DINA usando `app-catalogo-product-import` y el libro `referencias_datos_maestros_ejemplo.xlsx`.

Datos maestros del ejemplo:

- empresa `DINAFAST S.A.C.` / `11111111-1111-4111-8111-111111111111`;
- marca `DINA` / `22222222-2222-4222-8222-222222222222`;
- categoría `Pernería` / `33333333-3333-4333-8333-333333333333`;
- subcategoría `Pernos de zapata` / `44444444-4444-4444-8444-444444444442`.

Reglas adicionales:

- moneda `PEN` e IGV `18`;
- lista `DINA mayo 2026`;
- estado inicial `DRAFT`;
- no confirmar la importación;
- generar Excel, ZIP si hay imagen utilizable, reporte JSON y conteo CSV;
- cualquier precio ausente debe quedar por cotizar;
- no inferir la presentación desde `EMP. (CT)`.

## Validación

```powershell
python tools\validate_import_package.py --xlsx examples\DINA_pagina_32_parcial.xlsx --references examples\referencias_datos_maestros_ejemplo.xlsx --report examples\reporte_ejemplo.json
```

El ejemplo termina con cero errores y dos advertencias esperadas: imagen ausente y un atributo adicional no configurado.
