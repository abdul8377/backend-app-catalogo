# Backend App Catálogo

Servidor local Spring Boot para la aplicación Flutter offline-first. MySQL conserva la copia central y
la tablet continúa trabajando únicamente con SQLite cuando no hay red.

## Alcance actual

- Sincronización bidireccional de todos los módulos mediante registros JSON versionados.
- Registro y autenticación de dispositivos.
- Envío idempotente por `eventId`.
- Descarga incremental mediante cursor global.
- Bootstrap paginado para tablets nuevas o restauradas.
- Detección y almacenamiento de conflictos de versión.
- Proyección consultable de productos.
- CRUD web de productos; sus cambios entran en el mismo flujo de sincronización.
- Esquema MySQL administrado con Flyway.

La importación Excel, las imágenes y la pantalla de resolución de conflictos se implementarán sobre
este núcleo en las siguientes migraciones.

## Ejecución local

Requisitos: Java 17 y MySQL 8.

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/app_catalogo?createDatabaseIfNotExist=true&serverTimezone=UTC'
$env:DB_USERNAME='app_catalogo'
$env:DB_PASSWORD='cambiar-esta-clave'
$env:ADMIN_USERNAME='admin'
$env:ADMIN_PASSWORD='cambiar-admin'
.\mvnw.cmd spring-boot:run
```

- Administración: `http://localhost:8080/admin/products`
- API móvil: `http://IP-DE-LA-PC:8080/api/v1`

Los valores predeterminados de `application.yml` son únicamente para desarrollo.

## Autenticación del dispositivo

Registrar una instalación una sola vez:

```http
POST /api/v1/devices/register
Content-Type: application/json

{"name":"Tablet comercial","platform":"android"}
```

La respuesta contiene `deviceId` y `token`. El token se muestra una sola vez. Todas las demás llamadas
de la app deben incluir:

```http
X-Device-Id: <deviceId>
X-Device-Token: <token>
```

## Push tablet a PC

```http
POST /api/v1/sync/push
```

```json
{
  "deviceId": "uuid-de-tablet",
  "events": [
    {
      "eventId": "uuid-del-evento",
      "entityType": "PRODUCT",
      "entityId": "uuid-del-producto",
      "operation": "UPSERT",
      "baseVersion": 0,
      "occurredAt": "2026-08-04T02:00:00Z",
      "payload": {
        "productId": "uuid-del-producto",
        "code": "PROD-001",
        "name": "Producto de ejemplo",
        "status": "ACTIVE"
      }
    }
  ]
}
```

Estados por evento: `ACCEPTED`, `ALREADY_PROCESSED`, `REJECTED` o `CONFLICT`. La eliminación lógica
usa `operation: DELETE`. `baseVersion` debe ser la última versión aplicada localmente; una diferencia
crea un conflicto y nunca sobrescribe silenciosamente.

## Pull PC a tablet

```http
GET /api/v1/sync/pull?after=0&limit=300
```

La tablet guarda `nextCursor` solamente después de aplicar toda la respuesta en una transacción SQLite.
Si `hasMore` es `true`, repite la llamada con el nuevo cursor. Los cambios descargados se escriben con
un modo interno `saveFromSync`, sin volver a insertarlos en `sync_queue`.

## Bootstrap

```http
GET /api/v1/sync/bootstrap?page=0&limit=300
```

Devuelve la copia consolidada completa por páginas. Se solicita `nextPage` hasta que `hasMore` sea
`false`. Incluye tombstones para preservar eliminaciones lógicas.

## Tipos sincronizables

| Flutter | `entityType` |
| --- | --- |
| empresas | `COMPANY` |
| marcas | `BRAND` |
| categorias | `CATEGORY` |
| marca_categorias | `BRAND_CATEGORY` |
| unidades_medida | `MEASUREMENT_UNIT` |
| categoria_atributos | `CATEGORY_ATTRIBUTE` |
| categoria_atributo_opciones | `CATEGORY_ATTRIBUTE_OPTION` |
| categoria_atributo_unidades | `CATEGORY_ATTRIBUTE_UNIT` |
| productos | `PRODUCT` |
| producto_variantes_catalogo | `PRODUCT_VARIANT` |
| producto_familia_ejes | `PRODUCT_FAMILY_AXIS` |
| producto_atributos | `PRODUCT_ATTRIBUTE` |
| producto_atributo_opciones | `PRODUCT_ATTRIBUTE_OPTION` |
| clientes | `CLIENT` |
| hojas_pedido | `ORDER_SHEET` |
| pedidos | `ORDER` |
| pedido_items | `ORDER_ITEM` |
| cotizaciones | `QUOTE` |
| cotizacion_items | `QUOTE_ITEM` |
| preparacion_productos | `PREPARATION` |
| preparacion_disponible_movimientos | `PREPARATION_STOCK_MOVEMENT` |
| pedido_cargas | `ORDER_LOAD` |
| pedido_historial | `ORDER_HISTORY` |
| hoja_historial | `ORDER_SHEET_HISTORY` |

`sync_queue` no se sincroniza: es una cola local de transporte. Cada fila de negocio debe tener un UUID
compartido como `sync_id` cuando su clave SQLite actual sea entera.

## Pruebas

```powershell
.\mvnw.cmd test
```

Las pruebas usan H2 en modo MySQL y ejecutan la migración Flyway real antes de validar el flujo completo.
