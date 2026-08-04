# Contrato de sincronización 1.0

Este documento congela la forma JSON del contrato `1.0`. Un cliente con otra versión recibe `INCOMPATIBLE_API_CONTRACT` antes de procesar datos.

## Autenticación

Salvo descubrimiento y registro, toda ruta `/api/v1/**` requiere:

```http
X-Device-Id: <uuid>
X-Device-Token: <token de 256 bits>
```

## Push

```json
{
  "deviceId": "uuid",
  "apiContractVersion": "1.0",
  "events": [
    {
      "eventId": "uuid",
      "entityType": "PRODUCT",
      "entityId": "uuid",
      "operation": "UPSERT",
      "baseVersion": 3,
      "payloadVersion": 1,
      "schemaVersion": "1.0",
      "checksum": "sha256-hex-opcional-del-payload",
      "occurredAt": "2026-08-04T18:00:00Z",
      "payload": {}
    }
  ]
}
```

`eventId` es global e inmutable. Repetir exactamente el mismo evento devuelve `ALREADY_PROCESSED`. Reutilizarlo con otro contenido o desde otro dispositivo devuelve `REJECTED`. Cada resultado contiene `serverVersion`, `serverSequence` y, si aplica, `conflictId`.

`baseVersion` debe coincidir con la versión central. Nunca se aplica last-write-wins silencioso a catálogos, productos, clientes, pedidos o cotizaciones. Los movimientos e historiales son append-only y no aceptan `DELETE`.

## Agregado PRODUCT

Un `PRODUCT` transporta la familia completa; variantes, presentaciones y precios no se publican como ediciones parciales independientes.

```json
{
  "productId": "uuid",
  "code": "FAM-001",
  "name": "Producto",
  "description": "",
  "company": "Empresa",
  "companyId": "uuid-estable",
  "brand": "Marca",
  "brandId": "uuid-estable",
  "category": "Categoría",
  "categoryId": "uuid-estable",
  "subcategory": "Subcategoría",
  "subcategoryId": "uuid-estable",
  "productType": "SINGLE",
  "status": "ACTIVE",
  "attributes": {},
  "variants": [{"sku":"SKU-001","supplierCode":"","shortName":"","status":"ACTIVE","attributes":{}}],
  "presentations": [{"sku":"SKU-001","name":"Unidad","equivalence":1,"baseUnit":"UND","minimumSale":1,"status":"ACTIVE"}],
  "prices": [{"sku":"SKU-001","priceList":"General","presentation":"Unidad","currency":"PEN","taxRate":18,"price":10,"quoteRequired":false}],
  "images": [{"sku":"","storageKey":"files/...","type":"PRODUCT","primary":true}]
}
```

## Pull y ACK

```http
GET /api/v1/sync/pull?after=120&limit=300
```

La respuesta contiene `nextCursor`, `hasMore` y `changes`. Recibirla solo actualiza `lastDeliveredCursor` en el servidor. Después de aplicar todas las filas en una transacción SQLite:

```http
POST /api/v1/sync/pull/ack
Content-Type: application/json

{"cursor":150}
```

El ACK no puede retroceder ni superar lo entregado. Si la app falla antes del ACK, repite el pull anterior.

## Bootstrap

```http
GET /api/v1/sync/bootstrap?page=0&limit=300
```

La primera respuesta entrega `snapshotCursor`. Debe reenviarse en todas las páginas siguientes y usarse como primer `after` al terminar. El orden es catálogos → producto agregado → clientes → pedidos → cotizaciones → preparación/carga → historiales. Los registros eliminados permanecen como tombstones.

## Límites

- Push: 100 eventos por lote.
- Pull: 300 cambios por página.
- Bootstrap: 300 registros por página.
- El servidor responde `422` a límites inválidos o reglas de negocio, `401` a token ausente/revocado y `400` a JSON inválido.
