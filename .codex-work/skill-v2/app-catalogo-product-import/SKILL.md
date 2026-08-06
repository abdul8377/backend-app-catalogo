---
name: app-catalogo-product-import
description: Convierte catálogos PDF de proveedores en un paquete Excel v2 + ZIP de imágenes compatible con App Catálogo, usando los IDs globales reales de MySQL/SQLite, validando las relaciones de datos maestros, la moneda PEN, variantes, atributos, presentaciones, precios, imágenes y sincronización offline-first.
---

# Skill: Importación automatizada de productos e imágenes para App Catálogo

## 1. Objetivo

Automatizar este flujo sin registrar productos uno por uno:

`PDF de catálogo -> lote revisable -> Excel v2 -> ZIP de imágenes -> validación -> vista previa del backend -> confirmación -> MySQL -> sincronización con SQLite`.

La skill produce productos completos. Nunca crea ni importa empresas, marcas, categorías, atributos de categoría, clientes, pedidos, hojas de pedido, cotizaciones ni vendedores.

## 2. Cuándo usar esta skill

Úsala cuando el usuario pida:

- convertir páginas de un catálogo PDF a Excel para App Catálogo;
- preparar una carga masiva de productos;
- extraer variantes, atributos, presentaciones, precios o imágenes;
- generar el ZIP de imágenes asociado al Excel;
- revisar un lote antes de importarlo;
- corregir un Excel rechazado por el importador;
- reconstruir un lote conservando la trazabilidad de las páginas fuente.

No la uses para importar pedidos, clientes, empresas, marcas o categorías.

## 3. Entradas obligatorias

1. PDF o páginas concretas del catálogo.
2. `referencias-datos-maestros.xlsx` descargado del mismo backend y de la misma base MySQL donde se realizará la importación.
3. Plantilla Excel v2 descargada desde el backend.
4. Nombre del lote, rango de páginas y lista de precios.
5. ZIP de imágenes cuando el catálogo contenga imágenes utilizables.

El libro de referencias debe descargarse después de sincronizar la tablet y antes de preparar el lote. Sus identificadores no deben escribirse, inventarse ni reconstruirse manualmente.

Para pruebas aisladas se admite un JSON equivalente de datos maestros, pero en producción la fuente preferida es el Excel de referencias generado por el backend.

## 4. Salidas obligatorias

Cada ejecución genera:

- `productos_<lote>.xlsx`;
- `imagenes_<lote>.zip`, cuando existan imágenes utilizables;
- `reporte_validacion_<lote>.json`;
- `conteo_fuentes_<lote>.csv`;
- `faltantes_maestros_<lote>.csv`, solo cuando falten referencias;
- `decisiones_pendientes_<lote>.md`, solo cuando exista una ambigüedad que no pueda resolverse del catálogo.

## 5. Reglas que nunca se deben romper

1. La moneda siempre es `PEN`.
2. El valor impreso después de `P. V.` se conserva numéricamente y se interpreta como soles, aunque el PDF muestre `$`.
3. IGV predeterminado: `18`.
4. Un precio vacío se registra como `por_cotizar`; nunca como `0`.
5. Precio `0` solo se acepta con confirmación explícita de que es gratuito.
6. No crear datos maestros silenciosamente.
7. No insertar productos mediante SQL directo.
8. No confirmar una importación desde la skill. La skill solo prepara y valida los archivos; la confirmación ocurre en la vista previa del backend.
9. No mezclar dos familias comerciales diferentes en una sola familia.
10. No dividir una misma familia porque continúe en la página siguiente.
11. No ejecutar OCR de forma repetitiva. Primero se renderiza y se inspecciona visualmente; OCR es último recurso.
12. No extraer las 293 páginas como un único lote si cambian los diseños o las reglas comerciales.
13. No escribir fórmulas, macros ni vínculos externos en Excel.
14. No declarar un producto `ACTIVE` durante la primera carga masiva salvo que todos los controles estrictos pasen.
15. Las imágenes se guardan fuera de la base de datos; Excel solo contiene la ruta relativa del ZIP.
16. Nunca deformar, estirar o recortar agresivamente una imagen para llenar un cuadro.
17. Nunca inferir una presentación de venta a partir de `EMP.` o `CAJA` sin una leyenda comercial explícita.
18. Nunca inventar `EmpresaId`, `MarcaId`, `CategoriaId` o `SubcategoriaId`.
19. No usar como ID el entero local de SQLite.
20. No crear empresas, marcas, categorías o relaciones durante la importación de productos.
21. El archivo de referencias y la plantilla deben provenir del mismo backend de destino.
22. Todos los productos de una primera carga masiva se generan como `DRAFT`.

## 6. Datos maestros, IDs y relaciones

Antes de procesar productos deben existir y estar sincronizados:

- empresa;
- marca y empresa propietaria;
- categoría;
- subcategoría, representada como otra fila de `categorias` con `categoria_padre_id`;
- relación marca-categoría;
- unidades de medida;
- atributos de categoría, opciones y unidades permitidas;
- lista de precios que se utilizará.

### Fuente obligatoria

Usar el archivo `referencias-datos-maestros.xlsx` descargado desde `/admin/products/import`.

Hojas esperadas:

- `Ref_Empresas`;
- `Ref_Marcas`;
- `Ref_Categorias`;
- `Ref_Unidades`;
- `Ref_Atributos`;
- `Ref_Marca_Categorias`, cuando esté disponible;
- otras hojas de referencia que el backend incorpore posteriormente.

Los nombres sirven para revisión humana. Los IDs son la relación real.

Ejemplo correcto:

- `EmpresaId=11111111-1111-4111-8111-111111111111`;
- `Empresa=DINAFAST S.A.C.`;
- `MarcaId=22222222-2222-4222-8222-222222222222`;
- `Marca=DINA`;
- `CategoriaId=33333333-3333-4333-8333-333333333333`;
- `Categoria=Pernería`;
- `SubcategoriaId=44444444-4444-4444-8444-444444444444`;
- `Subcategoria=Pernos de zapata`.

### Reglas de resolución

1. Para importaciones reales, `EmpresaId`, `MarcaId` y `CategoriaId` son obligatorios.
2. `SubcategoriaId` es obligatorio cuando se informa una subcategoría.
3. Nunca generar IDs desde nombres, hashes, posiciones de fila o números locales de SQLite.
4. Nunca sustituir un ID por otro solo porque el nombre se parece.
5. El nombre del Excel debe coincidir con el nombre asociado al ID.
6. La marca debe pertenecer a la empresa indicada.
7. La subcategoría debe tener como padre la categoría indicada.
8. La marca debe estar relacionada con la categoría.
9. Si falta una referencia, generar `faltantes_maestros`; no crearla desde la skill.
10. Si el archivo de referencias no incluye la relación marca-categoría, el validador local emitirá una advertencia y la vista previa del backend seguirá siendo la validación definitiva.

### Modelo relacional esperado en MySQL

Datos maestros:

- `empresas`;
- `marcas`;
- `categorias`;
- `marca_categorias`;
- `unidades_medida`;
- `categoria_atributos`;
- `categoria_atributo_opciones`;
- `categoria_atributo_unidades`.

Producto y componentes:

- `products`;
- `producto_variantes_catalogo`;
- `producto_familia_ejes`;
- `producto_atributos`;
- `producto_atributo_opciones`;
- `producto_presentaciones`;
- `producto_precios`;
- `producto_imagenes`.

`sync_records` conserva el registro de sincronización. Las tablas anteriores son la proyección relacional consultable y validable.

### Orden de sincronización

1. `COMPANY`;
2. `BRAND`;
3. `CATEGORY`;
4. `BRAND_CATEGORY`;
5. `MEASUREMENT_UNIT`;
6. `CATEGORY_ATTRIBUTE`;
7. `CATEGORY_ATTRIBUTE_OPTION`;
8. `CATEGORY_ATTRIBUTE_UNIT`;
9. `PRODUCT`.

No preparar una importación masiva hasta que los maestros aparezcan en el libro de referencias del backend.

## 7. Segmentación del PDF en lotes

### Tamaño recomendado

- 5 a 15 páginas cuando hay diseños mixtos, matrices, imágenes o tablas partidas.
- 20 a 40 páginas solo cuando la estructura es muy uniforme.

### Una familia debe permanecer junta cuando

- el título continúa en la página siguiente;
- la tabla se corta al final de una página;
- una imagen o norma común aplica a las filas siguientes;
- los encabezados de columnas se repiten por continuidad.

### Una página se divide en varias familias cuando

- contiene varios títulos comerciales independientes;
- cada bloque tiene su propia leyenda de precio;
- cada bloque tiene imagen o norma distinta;
- las filas no comparten el mismo producto general.

## 8. Construcción de familia y variante

### Familia

Representa el producto comercial general. Debe contener:

- `CodigoFamilia` estable y legible;
- nombre general;
- descripción común;
- empresa y `EmpresaId` real;
- marca y `MarcaId` real;
- categoría y `CategoriaId` real;
- subcategoría y `SubcategoriaId` real cuando corresponda;
- tipo de registro;
- estado.

Formato recomendado de `CodigoFamilia`:

`<MARCA>-<LINEA>-<NOMBRE-CORTO>-<RASGO-COMUN>`

Ejemplo:

`DINA-MOTO-PERNO-FLANGE-M6`

No usar el nombre completo como identificador técnico ni cambiar el código en importaciones posteriores.

### Variante

Representa el artículo exacto que se vende. Cada código del proveedor normalmente es una variante.

Campos mínimos:

- `SKU`;
- `CodigoProveedor`;
- `NombreCorto`;
- estado;
- atributos técnicos propios.

Para catálogos DINA, mientras no exista otro código interno definitivo:

- usar el código DINA como `SKU`;
- repetirlo en `CodigoProveedor`;
- conservar mayúsculas;
- no corregir códigos aparentemente extraños sin evidencia visual.

## 9. Selección de tipo de producto

### `SINGLE`

Usar cuando existe exactamente un artículo vendible y no hay variaciones comerciales reales.

### `LIST`

Usar por defecto cuando:

- las variantes son irregulares;
- hay medidas, HEX, aplicaciones o acabados mezclados;
- no todas las combinaciones de atributos existen;
- cada código se entiende mejor como fila independiente.

### `MATRIX`

Usar solo cuando:

- hay exactamente dos ejes comerciales claros;
- el encabezado representa un eje y las filas el otro;
- las celdas representan combinaciones existentes o no existentes;
- los demás atributos son comunes o secundarios;
- la cuadrícula puede reconstruirse sin ambigüedad.

Si existe duda entre `LIST` y `MATRIX`, usar `LIST`.

## 10. Atributos

### Atributos comunes

En la hoja `Atributos`, dejar `SKU` vacío.

Ejemplos:

- tipo;
- material;
- acabado;
- norma;
- clase de resistencia;
- paso de rosca común;
- aplicación general.

### Atributos por variante

Indicar el SKU.

Ejemplos:

- diámetro;
- largo;
- HEX;
- color;
- marca compatible;
- modelo compatible;
- OEM;
- ubicación;
- posición;
- dureza;
- dimensión original.

### Reglas de extracción

1. Conservar el texto original de dimensiones en un atributo `Dimensión original` cuando la descomposición sea compleja.
2. Separar valor y unidad cuando sea inequívoco.
3. Conservar fracciones y números mixtos sin convertirlos de forma destructiva.
4. No inventar unidades.
5. No transformar listas de modelos en variantes; son atributos de aplicación.
6. No crear atributos que solo describen el diseño gráfico de la página.
7. Priorizar los atributos ya configurados en la categoría.
8. Una característica no prevista puede añadirse como atributo adicional, pero debe aparecer en el reporte.

## 11. Presentaciones de venta y empaques

La presentación responde a: `¿cómo lo pide y compra el cliente?`.

Mapeo por leyenda explícita:

- `PRECIO DE VENTA POR UNIDAD` o `POR PIEZA` -> `Unidad`, base `UND`, equivalencia `1`.
- `PRECIO DE VENTA POR DOCENA` -> `Docena`, base `UND`, equivalencia `12`.
- `PRECIO DE VENTA POR CIENTO` -> `Ciento`, base `UND`, equivalencia `100`.
- `PRECIO DE VENTA POR MILLAR` -> `Millar`, base `UND`, equivalencia `1000`.
- `PRECIO DE VENTA POR JUEGO` -> `Juego`; no asumir contenido si no está declarado.
- `PRECIO DE VENTA POR KG` -> `Kilogramo`, base `KG`, equivalencia `1`, permite decimales si corresponde.
- `PRECIO DE VENTA POR METRO` -> `Metro`, base `M`, equivalencia `1`, permite decimales si corresponde.

En catálogos DINA:

- `P. V.` es precio de venta;
- `EMP. (CT)`, `EMP. (uds.)` y `CAJA` describen empaque/logística o cantidad de empaque;
- no convertir `EMP.` en presentación de venta si la leyenda inferior dice que el precio es por unidad, pieza, ciento u otra presentación;
- conservar la cantidad de empaque como dato pendiente de logística si la plantilla v2 no la soporta directamente.

Cuando una presentación aplica a todas las variantes, dejar `SKU` vacío en `Presentaciones`.

## 12. Precios

1. Moneda: `PEN` siempre.
2. IGV: `18` salvo instrucción explícita diferente del proyecto.
3. Lista recomendada: `<MARCA> <MES> <AÑO>`, por ejemplo `DINA mayo 2026`.
4. Si el PDF dice `INCLUYE IGV`, mantener IGV 18 e indicar que el precio está configurado con IGV incluido en la configuración del producto.
5. No dividir automáticamente el precio por 12, 100 o 1000. El precio corresponde a la presentación indicada por la leyenda.
6. Celda de precio vacía:
   - `Configuracion=por_cotizar`;
   - `Precio` vacío;
   - `Cotizar=SI`.
7. Celda con precio:
   - `Configuracion=precio_fijo`;
   - `Precio=<valor numérico>`;
   - `Cotizar=NO`.
8. No eliminar filas sin precio; siguen siendo variantes vendibles por cotizar.
9. Mantener dos decimales cuando el catálogo los muestre.
10. No efectuar conversión monetaria.

## 13. Imágenes

### Propiedad de imagen

- Una imagen que representa todo el bloque o familia -> `SKU` vacío.
- Una imagen identificada claramente con un código específico -> usar ese SKU.
- `Imagen referencial` sin vínculo individual -> imagen familiar referencial.
- Si varias variantes comparten la misma foto, registrar una sola imagen familiar.

### Imagen principal

- Cada familia debe tener como máximo una principal.
- Si ninguna está marcada y existe al menos una, usar la primera como principal.
- Imágenes de variante normalmente no sustituyen toda la galería; son excepciones específicas.

### Extracción técnica

1. Renderizar la página a 200-300 dpi.
2. Recortar solo el producto, evitando precios, códigos, encabezados y marcas de agua innecesarias.
3. Mantener proporción.
4. Aplicar fondo blanco o transparente solo si mejora la lectura y no elimina partes del producto.
5. Formato preferido: WebP.
6. Calidad orientativa: 82-88.
7. Dimensión máxima orientativa: 1600 px en el lado mayor.
8. No ampliar imágenes pequeñas de forma agresiva.
9. No borrar texto que forme parte del producto o empaque fotografiado.
10. No generar imágenes artificiales cuando el catálogo ya contiene una imagen utilizable.
11. Si la página no contiene una imagen suficiente, dejar el producto sin imagen y emitir advertencia; no bloquear un borrador.

### Nombre de archivo

Imagen familiar:

`<marca>/<linea>/<codigo-familia>.webp`

Imagen de variante:

`<marca>/<linea>/<sku>.webp`

Reglas:

- minúsculas recomendadas;
- sin rutas absolutas;
- sin `..`;
- sin caracteres reservados;
- la ruta de Excel debe coincidir exactamente con la del ZIP.

## 14. Hojas del Excel v2

### `Fuentes`

Columnas:

`Lote, ArchivoPDF, Seccion, PaginaDesde, PaginaHasta, Observacion`

Una fila por bloque fuente procesado.

### `Productos`

Columnas:

`CodigoFamilia, ProductoId, Version, Nombre, Descripcion, Empresa, EmpresaId, Marca, MarcaId, Categoria, CategoriaId, Subcategoria, SubcategoriaId, Tipo, Estado`

Productos nuevos dejan `ProductoId` y `Version` vacíos.

Para una importación real son obligatorios `EmpresaId`, `MarcaId` y `CategoriaId`. Cuando exista subcategoría, también es obligatorio `SubcategoriaId`. Los nombres deben coincidir con los registros asociados a esos IDs.

### `Variantes`

Columnas:

`CodigoFamilia, SKU, CodigoProveedor, NombreCorto, Estado`

### `Atributos`

Columnas:

`CodigoFamilia, SKU, Atributo, Valor, Unidad`

### `Presentaciones`

Columnas:

`CodigoFamilia, SKU, Presentacion, UnidadBase, Equivalencia, VentaMinima, Incremento, PermiteDecimales, Estado`

### `Precios`

Columnas:

`CodigoFamilia, SKU, ListaPrecio, Presentacion, Moneda, IGV, Configuracion, Precio, Cotizar`

Aunque la plantilla contenga `Moneda`, escribir siempre `PEN`.

### `Imagenes`

Columnas:

`CodigoFamilia, SKU, Archivo, Tipo, Principal`

Tipo recomendado: `PRODUCT`.

## 15. Estado del producto

### Primera importación

Usar `DRAFT` por defecto.

### `ACTIVE` solo cuando

- empresa, marca y clasificación existen;
- hay al menos una variante activa;
- SKU únicos;
- existe al menos una presentación;
- todas las combinaciones vendibles tienen precio fijo o están por cotizar;
- no hay errores bloqueantes;
- existe imagen principal cuando la regla de activación del sistema la exige.

Un producto en borrador puede quedar sin imagen y con advertencias, pero no con referencias rotas.

## 16. Validación antes de entregar el paquete

### Estructura

- extensión `.xlsx`;
- nombres exactos de hojas;
- encabezados exactos;
- sin fórmulas;
- sin macros;
- sin filas completamente vacías intercaladas que rompan tablas;
- dentro de los límites de filas y tamaño.

### Integridad

- una fila en `Productos` por `CodigoFamilia`;
- `EmpresaId`, `MarcaId` y `CategoriaId` presentes;
- `SubcategoriaId` presente cuando exista subcategoría;
- nombres coherentes con los IDs;
- marca perteneciente a la empresa;
- subcategoría perteneciente a la categoría;
- relación marca-categoría existente;
- toda familia usada en otras hojas existe en `Productos`;
- todo SKU usado existe en `Variantes`;
- SKU no repetidos;
- presentaciones válidas;
- equivalencias, mínimos e incrementos positivos;
- precios en PEN;
- precios fijos con valor;
- por cotizar sin valor obligatorio;
- rutas de imágenes seguras;
- cada ruta declarada existe en el ZIP;
- máximo una imagen principal familiar.

### Conteos

Para cada página o bloque:

1. contar códigos visibles en el PDF;
2. contar variantes en Excel;
3. contar variantes válidas del validador;
4. explicar cualquier diferencia.

No entregar el lote si los conteos no están conciliados.

### Comando recomendado

```powershell
python tools\validate_import_package.py --xlsx productos_dina.xlsx --images imagenes_dina.zip --references referencias-datos-maestros.xlsx --report reporte.json
```

`--references` es obligatorio para importaciones reales. `--masters` se conserva únicamente para pruebas de compatibilidad y no permite aprobar un lote de producción.

## 17. Vista previa e importación

La skill no confirma automáticamente.

El usuario debe:

1. sincronizar la tablet;
2. abrir `/admin/products/import`;
3. descargar nuevamente `referencias-datos-maestros.xlsx`;
4. validar localmente el paquete contra ese archivo;
5. adjuntar Excel y ZIP;
6. pulsar `Validar y generar vista previa`;
7. revisar total, válidas, advertencias y errores;
8. descargar el informe cuando existan errores;
9. corregir el lote;
10. confirmar solo con cero errores bloqueantes.

La vista previa no debe modificar MySQL ni publicar eventos.

## 18. Sincronización y respaldo

Después de confirmar:

- el backend crea o actualiza el agregado PRODUCT;
- publica el cambio en el flujo de sincronización;
- la tablet recibe el producto por pull normal;
- la reconstrucción de una tablet vacía debe recuperar familia, variantes, atributos, presentaciones, precios, logística e imágenes;
- conservar siempre el Excel confirmado, ZIP original, informe y rango de páginas.

Orden de datos para reconstrucción:

1. empresas;
2. marcas;
3. categorías y subcategorías;
4. relaciones y atributos de categoría;
5. productos y componentes;
6. archivos de imagen.

## 19. Reglas específicas para el catálogo DINA

1. Todos los valores de `P. V.` se consideran soles (`PEN`).
2. El símbolo `$` se interpreta como símbolo gráfico del catálogo, no como USD.
3. `PRECIO DE VENTA (INC. IGV)` confirma IGV incluido.
4. La leyenda `POR UNIDAD`, `POR PIEZA`, `POR CIENTO`, etc. manda sobre las columnas de empaque.
5. `EMP. (CT)` suele indicar empaque en cientos; no es el precio ni necesariamente la presentación de venta.
6. Los códigos como `FYB...`, `BM...`, `IA...`, `C0...` se conservan exactamente.
7. Marca/modelo son atributos de aplicación, no empresas ni marcas del catálogo de la app.
8. `DINA®`, `@dinafastener`, `Índice` y `www.dina.com.pe` no son datos de producto.
9. Títulos comerciales en una misma página crean familias separadas.
10. Tablas métricas de dos ejes pueden ser `MATRIX`; listas de moto con aplicaciones y HEX irregulares son `LIST`.
11. Precios faltantes se conservan como por cotizar.
12. Imágenes rotuladas como referenciales se asignan a la familia salvo vínculo visual inequívoco con un SKU.

## 20. Ejemplo DINA, página 18

Fuente:

- Sección: `LÍNEA MOTO`;
- familia: `PNO. FLANGE / MOTO NIQUELADO FEY MÉTRICO M6X1.00`;
- tipo recomendado: `LIST`;
- código de familia: `DINA-MOTO-PERNO-FLANGE-M6`;
- lista: `DINA mayo 2026`;
- moneda: `PEN`;
- IGV: `18`;
- estado inicial: `DRAFT`.

Ejemplos de variantes visibles:

- `FYB802761`, M6 x 10, HEX 8, precio 7.92;
- `FYB802754`, M6 x 12, HEX 8, sin precio;
- `FYB802662`, M6 x 12, HEX 10, sin precio;
- `FYB802778`, M6 x 15, HEX 8, precio 8.66;
- `BM120622`, M6 x 22, HEX 8, precio 4.07.

Como la página dice `PRECIO DE VENTA (INC. IGV)` pero no declara explícitamente `POR CIENTO` o `POR UNIDAD` en el fragmento principal, la presentación debe revisarse visualmente antes de confirmarla. No inferirla solo desde `EMP. (CT)`.

## 21. Manejo de ambigüedades

Cuando no sea posible resolver un dato, no adivinar. Registrar:

- página;
- familia;
- SKU;
- campo ambiguo;
- opciones posibles;
- evidencia visual;
- decisión recomendada.

Ambigüedades bloqueantes:

- identidad de la familia;
- SKU ilegible;
- precio asociado a más de una fila posible;
- presentación de venta desconocida;
- categoría o marca maestra inexistente;
- imagen atribuible a varias familias sin claridad.

Ambigüedades no bloqueantes para borrador:

- descripción comercial secundaria;
- imagen ausente;
- atributo adicional no configurado;
- precio vacío, que pasa a por cotizar.

## 22. Criterio de finalización

La ejecución termina correctamente cuando:

- los archivos de salida existen;
- el validador local reporta cero errores;
- todas las diferencias de conteo están explicadas;
- todas las referencias maestras existen;
- los IDs proceden del libro de referencias del backend;
- empresa, marca, categoría, subcategoría y relación marca-categoría son coherentes;
- todas las rutas de imagen son válidas;
- cada precio está en PEN;
- las familias están separadas correctamente;
- el usuario puede cargar el paquete en la vista previa del backend sin modificarlo manualmente.
