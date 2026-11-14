# Data Model: Fotos y videos de la boda vía QR

**Feature**: `001-wedding-media-upload` | **Fecha**: 2026-11-14 | **Plan**: [plan.md](plan.md)

Almacenamiento: Amazon DynamoDB on-demand (metadatos) y Amazon S3 `boda-fotos-2026` (archivos).
Todas las tablas están en `us-east-1`, con el tag `proyecto=boda` y cifrado *Owned by Amazon
DynamoDB*. El navegador nunca accede a DynamoDB. Los motivos del diseño están en
[research.md](research.md) (R5, R8, R9).

Convenciones:
- Fechas de archivos y sesiones en UTC, ISO-8601 con `Z` (`2026-11-21T23:15:04.120Z`).
- Fechas de configuración del evento en hora local de Cali, sin zona (`2026-11-19T00:00`), y
  convertidas con `America/Bogota`.
- Atributos TTL en segundos epoch (Number), como exige DynamoDB.

---

## 1. Evento: tabla `boda-evento` (nueva)

Partition key `eventoId` (String). Un solo ítem en la fase 1: `boda-2026`.

| Atributo | Tipo | Ejemplo / regla |
| --- | --- | --- |
| `eventoId` | S | `boda-2026` |
| `nombre` | S | `Boda de Mara y Daniel` |
| `fechaBoda` | S | `2026-11-21` |
| `zonaHoraria` | S | `America/Bogota` |
| `aperturaLocal` | S | `2026-11-19T00:00` (FR-013, FR-017) |
| `cierreLocal` | S | `2026-11-22T12:00` |
| `disponibleHastaLocal` | S | `2026-12-01T23:59:59` (FR-032) |
| `cierreAnticipadoEn` | S (UTC) | ausente si no hubo cierre anticipado (FR-029) |
| `cierreAnticipadoPor` | S | usuario admin que cerró |
| `maxArchivosPorSesion` | N | `300` (FR-012a) |
| `maxBytesPorSesion` | N | `5368709120` (5 GiB) |
| `archivosTotal` | N | contador de archivos visibles en la galería (FR-030b) |
| `fotos` | N | ídem, solo fotos |
| `videos` | N | ídem, solo videos |
| `ultimaSubidaEn` | S (UTC) | hora de la última subida completada |

Reglas:
- Lo crea el responsable técnico desde CloudShell (comando en el plan, sección "Recursos AWS").
  El backend no lo crea, solo lo lee y actualiza (`cierreAnticipado*` y contadores).
- `cierreAnticipadoEn` se escribe con la condición `attribute_not_exists(cierreAnticipadoEn)`.
  Cerrar dos veces es idempotente; no hay reapertura desde la interfaz.
- El backend lo lee con `ConsistentRead=true` en cada petición que evalúa la ventana.

Estado derivado de la recepción (no se guarda):

```text
ahora < apertura                                  → NO_INICIADA
ahora ≥ cierre  o  cierreAnticipadoEn presente    → CERRADA
en otro caso                                      → ABIERTA
```

## 2. Archivo multimedia: tabla `boda-fotos` (existente, se le agrega un GSI)

Partition key `photoId` (String), sin cambios. Se agrega el GSI **`galeria-index`**:
- partition key `galeriaEvento` (S), sort key `galeriaOrden` (S), proyección `ALL`.
- Es **disperso**: los dos atributos existen solo mientras el archivo está `COMPLETADO`. Al
  eliminarlo se quitan (`REMOVE`) y el archivo sale de la galería.

| Atributo | Tipo | Regla |
| --- | --- | --- |
| `photoId` | S | UUID v4 generado por el backend (el `archivoId` del API) |
| `eventoId` | S | `boda-2026` |
| `sesionId` | S | sesión de subida que lo autorizó |
| `nombreInvitado` | S | 1–60 caracteres tras recortar espacios, sin caracteres de control (FR-004) |
| `nombreOriginal` | S | hasta 255 caracteres, solo informativo (FR-018) |
| `tipo` | S | `FOTO` \| `VIDEO` |
| `formato` | S | `jpg` \| `png` \| `heic` \| `webp` \| `mp4` \| `mov` |
| `contentType` | S | el firmado; ver tabla de formatos abajo |
| `tamano` | N | bytes declarados y verificados con `HeadObject` |
| `modo` | S | `SIMPLE` \| `MULTIPART` |
| `uploadId` | S | solo `MULTIPART` |
| `numeroPartes` | N | solo `MULTIPART` |
| `claveS3` | S | `eventos/boda-2026/originales/<photoId>.<formato>` |
| `claveMiniatura` | S | `eventos/boda-2026/miniaturas/<photoId>.jpg`, solo si la miniatura se verificó |
| `estado` | S | ver transiciones |
| `autorizadoEn` | S (UTC) | |
| `subidoEn` | S (UTC) | al completar; es la "fecha de subida" de la galería |
| `eliminadoEn`, `eliminadoPor` | S | al eliminar |
| `galeriaEvento` | S | `= eventoId`, solo si `COMPLETADO` |
| `galeriaOrden` | S | `<subidoEn>#<photoId>`, solo si `COMPLETADO` |

Formatos permitidos (FR-010, FR-011):

| Extensión | `formato` | `contentType` firmado | Tipo | Máximo |
| --- | --- | --- | --- | --- |
| `.jpg`, `.jpeg` | `jpg` | `image/jpeg` | FOTO | 40 MB (41.943.040 B) |
| `.png` | `png` | `image/png` | FOTO | 40 MB |
| `.heic` | `heic` | `image/heic` | FOTO | 40 MB |
| `.webp` | `webp` | `image/webp` | FOTO | 40 MB |
| `.mp4` | `mp4` | `video/mp4` | VIDEO | 500 MB (524.288.000 B) |
| `.mov` | `mov` | `video/quicktime` | VIDEO | 500 MB |

- Tamaño 0 → rechazado. `MULTIPART` si `tipo = VIDEO` y `tamano ≥ 100 MB` (104.857.600 B), con
  partes de 10 MiB.
- Firma del formato verificada al completar (research R2).
- Miniatura: `image/jpeg`, 1 B a 1 MB.

Transiciones de estado:

```text
            autorizar                  completar (recepción abierta y verificación OK)
 (nuevo) ───────────► PENDIENTE ─────────────────────────────────► COMPLETADO
                          │                                             │
                          │ cancelar, o completar rechazado             │ eliminar (novio)
                          ▼ (cerrada, tamaño o contenido inválido)      ▼
                      DESCARTADO                                    ELIMINADO
```

- `PENDIENTE → COMPLETADO`: transacción que actualiza el archivo (condición `estado = PENDIENTE`,
  pone `galeria*` y `subidoEn`) y suma 1 a `archivosTotal` y a `fotos`/`videos` en el evento.
  Repetir `completar` sobre un archivo ya `COMPLETADO` responde éxito sin volver a sumar
  (reintento seguro).
- `PENDIENTE → DESCARTADO`: el backend borra el objeto o aborta el multipart. El tope de la sesión
  **no** se devuelve (FR-012a cuenta autorizaciones).
- `COMPLETADO → ELIMINADO` (FR-028): borra original y miniatura de S3 y, en una transacción, quita
  `galeria*` y resta 1 a los contadores. No hay papelera ni vuelta atrás.
- Un `PENDIENTE` que nunca se completa no se ve en la galería. Si es multipart, la regla de
  lifecycle lo limpia en 1 día (FR-021). Si es simple y el objeto llegó a S3, queda huérfano; se
  revisa después del cierre (quickstart, paso "Huérfanos").

Consultas:
- Galería (FR-026): `Query` sobre `galeria-index`, `galeriaEvento = boda-2026`,
  `ScanIndexForward=false`, `Limit=24`, `ExclusiveStartKey` = cursor.
- Cualquier otra operación: `GetItem` / `UpdateItem` por `photoId`. Sin `Scan`.

## 3. Usuario administrador: tabla `boda-admins` (nueva)

Partition key `usuario` (String). Exactamente dos ítems: `mara` y `daniel` (FR-031).

| Atributo | Tipo | Regla |
| --- | --- | --- |
| `usuario` | S | en minúsculas |
| `nombreVisible` | S | `Mara`, `Daniel` |
| `hashContrasena` | S | bcrypt costo 12 (`$2a$12$...`); nunca la contraseña (FR-024) |
| `intentosFallidos` | N | se reinicia a 0 al entrar bien |
| `bloqueadaHasta` | S (UTC) | presente si hay 5 fallos seguidos; ahora + 15 min (FR-025) |
| `actualizadoEn` | S (UTC) | |

- Se crean y restablecen desde CloudShell con el hash que imprime
  `java -jar app.jar hash-contrasena` (research R7). El backend solo lee y actualiza
  `intentosFallidos` y `bloqueadaHasta`.
- Mientras `bloqueadaHasta > ahora`, el login responde el mensaje genérico sin comprobar la
  contraseña.

## 4. Sesiones: tabla `boda-sesiones` (nueva)

Partition key `id` (String). TTL activado sobre el atributo `expiraEnEpoch`. Guarda dos tipos de
sesión.

**Sesión de subida** (`tipo = SUBIDA`, FR-004a, FR-012a):

| Atributo | Tipo | Regla |
| --- | --- | --- |
| `id` | S | `sub#<128 bits aleatorios en Base64URL>`; el navegador conoce la parte sin el prefijo |
| `tipo` | S | `SUBIDA` |
| `eventoId` | S | `boda-2026` |
| `ultimoNombre` | S | último nombre declarado |
| `archivos` | N | autorizaciones emitidas |
| `bytes` | N | suma de tamaños autorizados |
| `creadaEn` | S (UTC) | |
| `expiraEnEpoch` | N | cierre de la recepción + 1 día |

Reglas: la autorización suma con `UpdateItem` condicional
(`archivos < :max AND bytes <= :maxBytes - :tamano`). Si falla la condición →
`409 TOPE_SESION`. Una sesión de otro evento o expirada → `401 SESION_INVALIDA`, y el front crea
una nueva.

**Sesión de administrador** (`tipo = ADMIN`, FR-030):

| Atributo | Tipo | Regla |
| --- | --- | --- |
| `id` | S | `adm#<SHA-256 en hex del token>`; el token en claro nunca se guarda |
| `tipo` | S | `ADMIN` |
| `usuario` | S | `mara` \| `daniel` |
| `creadaEn` | S (UTC) | |
| `ultimoUsoEn` | S (UTC) | se actualiza como máximo cada 5 minutos |
| `expiraEnEpoch` | N | `ultimoUsoEn` + 8 h (inactividad) |

Reglas:
- El backend comprueba `expiraEnEpoch` en cada petición: el TTL de DynamoDB borra con hasta 48 h
  de retraso y no sirve como control.
- Cerrar sesión borra el ítem (`DeleteItem`).

## 5. Objetos en S3 `boda-fotos-2026`

```text
eventos/boda-2026/originales/<photoId>.<formato>    # original, Content-Type firmado
eventos/boda-2026/miniaturas/<photoId>.jpg          # opcional, image/jpeg ≤ 1 MB
```

- Privado, SSE-S3, sin versionado. La lifecycle solo aborta multipart incompletos a 1 día.
- Retención (FR-033): nada se borra automáticamente. La eliminación definitiva la hace el
  responsable técnico, a pedido de los novios, borrando el prefijo `eventos/boda-2026/`.
- Lectura con `GetObject` prefirmado de 15 minutos. La descarga agrega
  `response-content-disposition=attachment; filename*=UTF-8''<nombreOriginal codificado>`.

## Relaciones

```text
Evento 1 ──── * SesionSubida 1 ──── * ArchivoMultimedia ──── 0..1 miniatura (objeto S3)
Evento 1 ──── * ArchivoMultimedia (vía galeria-index)
UsuarioAdmin 1 ──── * SesionAdmin
```
