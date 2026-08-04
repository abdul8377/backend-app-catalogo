# Contrato oficial de sincronización 1.0

Este es el único documento normativo del contrato entre el backend y la app móvil. Los archivos de `docs/contracts/examples/` son fixtures ejecutables de este mismo contrato y no definen variantes.

## Versiones congeladas

| Campo | Valor exacto |
| --- | --- |
| `apiContractVersion` | `"1.0"` |
| `payloadVersion` | `1` |
| `schemaVersion` | `"1.0"` |

El backend rechaza un lote con otro `apiContractVersion`. Cada evento con otro `payloadVersion` o `schemaVersion` recibe un resultado `REJECTED` sin modificar datos.

## Descubrimiento, identidad y vinculación

`GET /api/v1/discovery` es público y responde exclusivamente:

```json
{
  "serverId": "uuid-estable-de-la-pc",
  "serverName": "Catálogo oficina principal",
  "serviceType": "_appcatalogo._tcp.local.",
  "port": 8081,
  "apiContractVersion": "1.0",
  "pairingAvailable": true
}
```

El `serverId`, no la IP, identifica a la PC. mDNS publica `serverId`, `serverName` y `apiContractVersion` como TXT. El QR contiene exactamente `serverId`, `serverName`, `pairingCode`, `serviceType` y `apiContractVersion`; una IP actual no forma parte de la identidad.

Registro público de un dispositivo, una sola vez:

```http
POST /api/v1/devices/register
Content-Type: application/json
```

```json
{
  "name": "Tablet de ventas",
  "platform": "ANDROID",
  "pairingCode": "12345678",
  "appVersion": "1.0.0",
  "apiContractVersion": "1.0"
}
```

La respuesta contiene exclusivamente `deviceId`, `token`, `apiContractVersion`, `bootstrapStatus` y `registeredAt`. El código es temporal y de un solo uso. El token es distinto por dispositivo, se entrega una vez y el backend conserva solo su hash. No existe registro abierto sin código.

Después del registro, toda ruta `/api/v1/**`, salvo descubrimiento y registro, requiere:

```http
X-Device-Id: <deviceId>
X-Device-Token: <token>
```

## Push tablet → PC

```http
POST /api/v1/sync/push
Content-Type: application/json
```

```json
{
  "deviceId": "uuid",
  "apiContractVersion": "1.0",
  "events": [
    {
      "eventId": "uuid",
      "entityType": "CLIENT",
      "entityId": "uuid",
      "operation": "UPSERT",
      "baseVersion": 2,
      "payloadVersion": 1,
      "schemaVersion": "1.0",
      "checksum": null,
      "occurredAt": "2026-08-04T20:00:00Z",
      "payload": {}
    }
  ]
}
```

Campos exactos del resultado: `eventId`, `status`, `version`, `sequence`, `conflictId`, `message`. No se usan los alias `serverVersion` ni `serverSequence`.

```json
{
  "results": [
    {
      "eventId": "uuid",
      "status": "ACCEPTED",
      "version": 3,
      "sequence": 123,
      "conflictId": null,
      "message": null
    }
  ]
}
```

`eventId` es global e inmutable. Repetir el mismo evento devuelve `ALREADY_PROCESSED` con los mismos `version`, `sequence` y `conflictId`. Reutilizarlo con otro contenido o dispositivo devuelve `REJECTED`. `baseVersion` debe coincidir con la versión central; una diferencia genera `CONFLICT` y nunca sobrescribe silenciosamente. Los movimientos e historiales son append-only y no aceptan `DELETE`.

## Pull PC → tablet y ACK

```http
GET /api/v1/sync/pull?after=120&limit=300
```

La respuesta contiene `nextCursor`, `hasMore` y `changes`. Cada cambio contiene exactamente `sequence`, `entityType`, `entityId`, `operation`, `version`, `originDeviceId`, `conflictId`, `payload` y `changedAt`.

Recibir el pull solo avanza el cursor entregado. Después de aplicar toda la página en una única transacción SQLite:

```http
POST /api/v1/sync/pull/ack
Content-Type: application/json

{"cursor":150}
```

La respuesta contiene `acknowledgedCursor` y `acknowledgedAt`. Un ACK no puede retroceder ni superar el último cursor entregado. Si la app falla antes del ACK, debe repetir el pull anterior.

## Bootstrap de una instantánea

```http
GET /api/v1/sync/bootstrap?page=0&limit=300
GET /api/v1/sync/bootstrap?page=1&limit=300&snapshotCursor=123
```

La primera página fija `snapshotCursor`; todas las siguientes deben reenviarlo. Solo se incluyen registros cuya `lastSequence` no supera esa instantánea. Al finalizar, la app usa `snapshotCursor` como primer `after` del pull. Los tombstones se conservan.

Orden exacto de dependencias:

1. `COMPANY`
2. `BRAND`
3. `CATEGORY`
4. `BRAND_CATEGORY`
5. `MEASUREMENT_UNIT`
6. `CATEGORY_ATTRIBUTE`
7. `CATEGORY_ATTRIBUTE_OPTION`
8. `CATEGORY_ATTRIBUTE_UNIT`
9. `LEGACY_ATTRIBUTE_DEFINITION`
10. `PRODUCT`
11. `CLIENT`
12. `ORDER_SHEET`
13. `ORDER`
14. `ORDER_ITEM`
15. `QUOTE`
16. `QUOTE_ITEM`
17. `PREPARATION`
18. `PREPARATION_STOCK_MOVEMENT`
19. `ORDER_LOAD`
20. `ORDER_HISTORY`
21. `ORDER_SHEET_HISTORY`

`PRODUCT_VARIANT`, `PRODUCT_FAMILY_AXIS`, `PRODUCT_ATTRIBUTE` y `PRODUCT_ATTRIBUTE_OPTION` no son tipos sincronizables.

## Estado e inicialización

`GET /api/v1/sync/status` responde exactamente:

```json
{
  "serverId": "uuid-estable-de-la-pc",
  "apiContractVersion": "1.0",
  "recordCount": 0,
  "changeCount": 0,
  "pendingConflictCount": 0
}
```

La app decide explícitamente la fuente inicial: PC vacía → subir snapshot de tablet; tablet vacía → bootstrap; ambas con datos → pedir decisión. El backend no fusiona automáticamente dos instalaciones existentes.

## Agregado PRODUCT

`PRODUCT` transporta siempre la familia completa con los campos exactos `productId`, `code`, `name`, `description`, `company`, `companyId`, `brand`, `brandId`, `category`, `categoryId`, `subcategory`, `subcategoryId`, `productType`, `status`, `attributes`, `variants`, `presentations`, `prices` e `images`.

Las imágenes contienen `storageKey` relativo generado por el backend; nunca una ruta física de Windows. CRUD web, importación Excel y tablet publican mediante el mismo flujo `ProductService → ServerChangePublisher → sync_records → sync_change_log`.

## Archivos

Flujo exacto: `POST /api/v1/files/intents` → `PUT /api/v1/files/intents/{fileId}/content` → `POST /api/v1/files/intents/{fileId}/complete`.

El intent define `fileName`, `fileType`, `contentType`, `sizeBytes`, `checksumSha256`, `visibility`, `ownerType` y `ownerId`. La respuesta agrega `fileId`, `storageKey`, `status`, `expiresAt`, `uploadUrl` y `completeUrl`. Tipos lógicos: `PRODUCT_IMAGE`, `PRODUCT_THUMBNAIL`, `DOCUMENT`. Estados: `INTENT`, `UPLOADED`, `READY`, `EXPIRED`.

Si el contenido no coincide en tamaño, MIME o checksum, no se publica. Un intent incompleto expira, el archivo parcial se elimina y debe solicitarse otro. Solo imágenes de producto pueden ser públicas.

## Conflictos

Un resultado `CONFLICT` incluye `conflictId`. Al resolverlo, el backend publica una nueva versión normal, registra el mismo `conflictId` en `sync_change_log`, marca el conflicto como resuelto y expone `resolutionVersion` y `resolutionSequence`. El pull entrega ese mismo `conflictId` a la tablet.

## Límites y errores

- Push: máximo 100 eventos por lote.
- Pull: máximo 300 cambios por página.
- Bootstrap: máximo 300 registros por página.
- `400`: JSON o validación estructural inválida.
- `401`: token ausente, inválido o revocado.
- `422`: regla de negocio, versión o límite inválido.
