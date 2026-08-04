# Backend App Catálogo

Servidor local Spring Boot para la aplicación Flutter offline-first. MySQL conserva la copia central en la PC y cada tablet trabaja con su base SQLite, incluso sin red. La sincronización es completa y bidireccional; el único CRUD comercial web del backend es Productos.

## Funcionalidad incluida

- Descubrimiento por mDNS/DNS-SD (`_appcatalogo._tcp.local.`), `serverId` persistente y endpoint público mínimo.
- Emparejamiento mediante código y QR de un solo uso con vencimiento, token por dispositivo, rotación, revocación y auditoría.
- Push por lotes con contrato versionado, idempotencia, checksum, rechazo de reutilización de `eventId` y conflictos de versión.
- Pull incremental con cursor entregado y confirmación separada mediante ACK.
- Bootstrap paginado por snapshot (`last_sequence`), en orden completo de dependencias y con tombstones.
- Escritura protegida con bloqueo por entidad, lock pesimista y versión técnica optimista.
- Producto como agregado: clasificación estable, variantes, atributos, presentaciones, precios e imágenes.
- CRUD web de productos, importación Excel con vista previa, confirmación por producto e informe XLSX.
- Almacenamiento local abstraído, claves relativas, intents con expiración y carga en tres pasos.
- Resolución web de conflictos, retención temporal, health/info, request ID, logs rotativos y scripts de respaldo/restauración.

No se crean CRUD web para empresas, marcas, categorías, clientes, pedidos, cotizaciones, preparación, cargas ni historiales. Esas entidades viajan por sincronización.

## Requisitos y configuración

- Java 17
- MySQL 8 accesible solo desde la PC (`127.0.0.1`/`localhost`)
- Valores predeterminados para el entorno local con Laragon:

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/app_catalogo?createDatabaseIfNotExist=true&serverTimezone=UTC'
$env:DB_USERNAME='root'
$env:DB_PASSWORD=''
$env:ADMIN_USERNAME='admin'
$env:ADMIN_PASSWORD='admin'
$env:STORAGE_ROOT='D:/AppCatalogoStorage'
$env:SERVER_NAME='Catálogo oficina principal'
$env:SERVER_PORT='8081'
# Opcional si la PC tiene VPN o varios adaptadores:
# $env:MDNS_BIND_ADDRESS='192.168.1.20'
.\mvnw.cmd spring-boot:run
```

Estas variables son opcionales mientras se usen esos valores locales. Para otro entorno deben sobrescribirse con credenciales seguras.

El servidor escucha en `0.0.0.0:8081` para la red privada. MySQL no debe exponerse a la red. Las páginas principales son:

- `/admin/products`: CRUD de productos.
- `/admin/products/import`: plantilla, vista previa y confirmación Excel.
- `/admin/devices`: servidor, QR, dispositivos, rotación y revocación.
- `/admin/conflicts`: resolución de conflictos pendientes.
- `/actuator/health` y `/actuator/info`: estado operativo y versión del contrato.

## Emparejamiento y descubrimiento

1. El administrador genera un código en `/admin/devices`.
2. La tablet descubre `_appcatalogo._tcp.local.` o usa una URL manual.
3. Puede verificar el servidor con `GET /api/v1/discovery`.
4. Escanea el QR y registra la instalación:

```http
POST /api/v1/devices/register
Content-Type: application/json

{
  "name": "Tablet comercial",
  "platform": "android",
  "pairingCode": "12345678",
  "appVersion": "1.0.0",
  "apiContractVersion": "1.0"
}
```

El token de la respuesta se muestra una vez. Las llamadas privadas usan `X-Device-Id` y `X-Device-Token`.

## Sincronización

El único contrato oficial está documentado en [docs/sync-contract-v1.md](docs/sync-contract-v1.md). Los JSON consumibles por backend y Flutter están en [docs/contracts/examples](docs/contracts/examples).

- Tablet → PC: `POST /api/v1/sync/push`.
- PC → tablet: `GET /api/v1/sync/pull?after=0&limit=300`.
- Confirmación después de aplicar en una transacción SQLite: `POST /api/v1/sync/pull/ack`.
- Copia completa inicial: `GET /api/v1/sync/bootstrap?page=0&limit=300`.

La tablet no debe avanzar su cursor reconocido al recibir el pull. Primero aplica todo localmente, después confirma el `nextCursor`. Un pull repetido antes del ACK es correcto y debe ser idempotente por `entityId` + `version`.

## Importación Excel

La plantilla versionada se descarga desde `/admin/products/import/template`. Contiene las hojas `Productos`, `Variantes`, `Presentaciones`, `Precios` e `Imagenes`.

Reglas principales:

- Solo `.xlsx`, sin macros ni fórmulas; máximo 20 MB y 10 000 filas.
- El original se conserva temporalmente y se identifica por SHA-256.
- La vista previa no modifica `products`, `sync_records` ni `sync_change_log`.
- Crear: `ProductoId` y `Version` vacíos.
- Actualizar: ambos campos son obligatorios; el código no se usa como identidad de actualización.
- La confirmación publica cada producto completo en una unidad transaccional independiente.
- El informe final XLSX conserva acción, estado, mensajes, producto y versión por fila agregada.
- Los binarios de imágenes se cargan por separado; Excel solo guarda claves relativas.

## Archivos

La API usa intent → upload → complete:

- `POST /api/v1/files/intents`
- `PUT /api/v1/files/intents/{id}/content`
- `POST /api/v1/files/intents/{id}/complete`
- `GET /api/v1/files/{id}` para privados
- `GET /public/files/{id}` solo para imágenes de producto declaradas públicas

MySQL conserva propietario, tipo lógico, MIME, tamaño, checksum, visibilidad, estado, expiración y claves relativas. El contenido se guarda bajo `STORAGE_ROOT`; no se almacenan BLOB ni rutas absolutas. Un intent incompleto expira después de una hora y su contenido parcial es eliminado.

## Migraciones

`V1`–`V7` permanecen inmutables. `V8` agrega el snapshot estable por secuencia, correlación de conflictos y metadatos/expiración de archivos.

## Operación en Windows

1. Generar el JAR: `.\mvnw.cmd clean package`.
2. Instalar como servicio desde PowerShell administrador: `.\scripts\install-windows-service.ps1 -JarPath .\target\backend-app-catalogo-0.0.1-SNAPSHOT.jar`.
3. El instalador abre únicamente en perfil privado TCP 8081 entrada y UDP 5353 entrada/salida; nunca abre MySQL 3306.
4. Desinstalar servicio y reglas: `.\scripts\uninstall-windows-service.ps1`.
5. Crear un respaldo: `.\scripts\backup-mysql.ps1`.
6. Restaurar deliberadamente: `.\scripts\restore-mysql.ps1 -BackupFile <archivo.sql.zip> -ConfirmRestore`.

## Pruebas

```powershell
$env:JAVA_HOME='D:\java\jdk17'
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'
.\mvnw.cmd test
```

La suite ejecuta Flyway real sobre H2 en modo MySQL y cubre fixtures contractuales, emparejamiento, revocación, idempotencia, versiones, conflictos correlacionados, pull/ACK, snapshot de bootstrap, PRODUCT agregado, producto web, Excel y expiración de archivos.
