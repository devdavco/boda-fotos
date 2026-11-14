# Contrato del API (backend Spring Boot)

**Feature**: `001-wedding-media-upload` | **Fecha**: 2026-11-14 | **Plan**: [../plan.md](../plan.md)

- Producción: los front-ends llaman a `/api/...` en su propio dominio de Amplify. El rewrite lo
  envía a `https://ddl2si3h6lu7z.cloudfront.net/api/...` → ALB → EC2 `:8080`.
- JSON UTF-8 en peticiones y respuestas. Fechas en ISO-8601 UTC (`...Z`).
- Health check del ALB: `GET /actuator/health` → `200 {"status":"UP"}`. Está fuera de `/api` y
  no es alcanzable desde internet (el ALB solo reenvía `/api/*`).
- Modelo de datos: [../data-model.md](../data-model.md).

## Errores

Toda respuesta de error tiene este cuerpo. El front muestra `mensaje` tal cual (Principio VI):

```json
{ "codigo": "ARCHIVO_MUY_GRANDE", "mensaje": "El video supera el máximo de 500 MB. Elige uno más corto." }
```

| HTTP | `codigo` | Cuándo | `mensaje` (español, para el usuario) |
| --- | --- | --- | --- |
| 400 | `NOMBRE_INVALIDO` | nombre vacío o de más de 60 caracteres | Escribe tu nombre (máximo 60 caracteres). |
| 400 | `TIPO_NO_PERMITIDO` | extensión o tipo fuera de la lista | Este tipo de archivo no se puede subir. Usa fotos JPG, PNG, HEIC o WEBP, o videos MP4 o MOV. |
| 400 | `ARCHIVO_VACIO` | tamaño 0 | El archivo está vacío. Elige otro. |
| 400 | `ARCHIVO_MUY_GRANDE` | > 40 MB foto o > 500 MB video | La foto supera el máximo de 40 MB. / El video supera el máximo de 500 MB. Elige uno más corto. |
| 400 | `PETICION_INVALIDA` | JSON mal formado o campos faltantes | No pudimos procesar la solicitud. Inténtalo de nuevo. |
| 401 | `SESION_INVALIDA` | `X-Sesion-Subida` ausente, desconocido o vencido | Tu sesión se reinició. Vuelve a escribir tu nombre. |
| 401 | `NO_AUTENTICADO` | token de admin ausente, inválido o vencido | Tu sesión terminó. Inicia sesión de nuevo. |
| 401 | `CREDENCIALES_INVALIDAS` | login fallido o cuenta bloqueada | Usuario o contraseña incorrectos. Si fallaste varias veces, espera 15 minutos e inténtalo de nuevo. |
| 403 | `RECEPCION_NO_INICIADA` | antes de la apertura | La recepción de fotos abre el jueves 19 de noviembre. |
| 403 | `RECEPCION_CERRADA` | después del cierre o del cierre anticipado | La recepción de fotos y videos ya cerró. ¡Gracias por compartir! |
| 404 | `NO_ENCONTRADO` | archivo inexistente, eliminado o de otra sesión | Este archivo ya no está disponible. |
| 409 | `TOPE_SESION` | se alcanzó el tope de 300 archivos o 5 GB | Llegaste al límite de 300 archivos o 5 GB desde este celular. ¡Gracias por compartir tanto! |
| 409 | `SUBIDA_INCOMPLETA` | al completar, el objeto o alguna parte no está en S3 | La subida no terminó. Toca "Reintentar". |
| 422 | `CONTENIDO_INCONSISTENTE` | tamaño, tipo o firma del archivo no coinciden con lo autorizado | El archivo no parece ser una foto o video válido. Elige otro. |
| 410 | `PERIODO_TERMINADO` | galería después del 2026-12-01 23:59 (Cali) | El periodo para ver y descargar terminó el 1 de diciembre. Para recuperar el material, escribe al responsable técnico. |
| 500 | `ERROR_INTERNO` | cualquier otro | Algo falló de nuestro lado. Inténtalo de nuevo en unos segundos. |

---

## API de invitados (sin autenticación de usuario)

### `GET /api/recepcion/estado`

Estado de la ventana de recepción, evaluado en el servidor (FR-013, FR-014).

```json
200 { "estado": "ABIERTA", "aperturaEn": "2026-11-19T05:00:00Z", "cierreEn": "2026-11-22T17:00:00Z", "ahora": "2026-11-21T23:10:00Z" }
```

`estado`: `NO_INICIADA` | `ABIERTA` | `CERRADA`. `cierreEn` es el cierre efectivo (el anticipado
si existe).

### `POST /api/sesiones-subida`

Crea una sesión de subida (FR-004a). Solo con la recepción abierta.

```json
→ { "nombre": "Tía Carmen" }
← 201 { "sesionId": "Qm9kYS0yMDI2LXN1Yi1leGFtcGxl", "nombre": "Tía Carmen", "maxArchivos": 300, "maxBytes": 5368709120 }
```

Errores: `NOMBRE_INVALIDO`, `RECEPCION_NO_INICIADA`, `RECEPCION_CERRADA`.

Todos los endpoints siguientes de invitados exigen el encabezado `X-Sesion-Subida: <sesionId>` y
solo operan sobre archivos de esa sesión (si no, `404 NO_ENCONTRADO`).

### `POST /api/subidas`

Autoriza un archivo. Valida nombre, tipo, tamaño, ventana y tope (FR-010 a FR-015). El navegador
la llama cuando el archivo empieza a subir (su turno en la cola), no al elegirlo, para que la URL
no venza mientras espera.

```json
→ {
    "nombreInvitado": "Tía Carmen",
    "nombreArchivo": "IMG_2041.MOV",
    "tipoContenido": "video/quicktime",
    "tamano": 312475648,
    "conMiniatura": true,
    "tamanoMiniatura": 48210
  }
```

- `tipoContenido` es opcional: el backend decide por la extensión y rechaza si el tipo enviado
  contradice la extensión.
- `conMiniatura`: el navegador logró generar una miniatura y la subirá.
- `tamanoMiniatura`: bytes de esa miniatura (de 1 a 1.048.576). La URL de la miniatura firma ese
  tamaño, igual que la del archivo, así que hay que enviarlo junto con `conMiniatura`; si falta o
  está fuera de rango, la respuesta no trae `urlMiniatura` y el archivo se sube sin miniatura (nunca
  se rechaza por la miniatura).

Respuesta en modo simple (< 100 MB, o cualquier foto):

```json
← 201 {
    "archivoId": "3f6c1e9e-8a8f-4c51-9d7e-0b9e3c7f2a10",
    "modo": "SIMPLE",
    "url": "https://boda-fotos-2026.s3.us-east-1.amazonaws.com/eventos/boda-2026/originales/3f6c...jpg?X-Amz-...",
    "encabezados": { "Content-Type": "image/jpeg" },
    "urlMiniatura": "https://...miniaturas/3f6c....jpg?X-Amz-...",
    "expiraEn": "2026-11-21T23:15:00Z"
  }
```

Respuesta en modo multipart (video ≥ 100 MB):

```json
← 201 {
    "archivoId": "…",
    "modo": "MULTIPART",
    "tamanoParte": 10485760,
    "numeroPartes": 30,
    "urlMiniatura": "https://…",
    "expiraEn": "2026-11-21T23:15:00Z"
  }
```

- El navegador hace `PUT` a `url` con exactamente los `encabezados` indicados y el archivo como
  cuerpo. La miniatura va con `PUT` a `urlMiniatura` y `Content-Type: image/jpeg`.
- `urlMiniatura` solo viene si `conMiniatura` es `true` y `tamanoMiniatura` es válido.
- Errores: `NOMBRE_INVALIDO`, `TIPO_NO_PERMITIDO`, `ARCHIVO_VACIO`, `ARCHIVO_MUY_GRANDE`,
  `TOPE_SESION`, `RECEPCION_NO_INICIADA`, `RECEPCION_CERRADA`, `SESION_INVALIDA`.

### `POST /api/subidas/{archivoId}/url`

Nueva URL para reintentar un archivo simple ya autorizado (FR-007). No suma al tope. Misma forma
que la respuesta simple de `POST /api/subidas`. Errores: `RECEPCION_CERRADA`, `NO_ENCONTRADO`.

### `POST /api/subidas/{archivoId}/partes`

URLs prefirmadas para partes de un multipart (FR-008). El navegador las pide **por parte, justo
antes de enviar cada una**: como máximo 3 números por petición (las partes que salen en paralelo).
Nunca se piden todas al inicio, porque duran 5 minutos y un video de 500 MB por 4G tarda más. Si
el `PUT` de una parte responde 403 por URL vencida, se pide de nuevo esa parte y se reintenta.

```json
→ { "partes": [1, 2, 3] }
← 200 { "urls": [ { "numero": 1, "url": "https://…&partNumber=1&uploadId=…" } ], "expiraEn": "…" }
```

El navegador guarda el `ETag` de la respuesta de cada parte (el CORS del bucket lo expone).

Errores: `RECEPCION_CERRADA`, `NO_ENCONTRADO`, `PETICION_INVALIDA` (número de parte fuera de
rango o más de 3 partes en una petición).

### `POST /api/subidas/{archivoId}/completar`

Cierra la subida. Rechaza si la recepción está cerrada, en ambos modos (FR-016, US4-2).
Verifica tamaño, tipo y firma del formato en S3. Es idempotente.

```json
→ { "partes": [ { "numero": 1, "etag": "\"a54357aff0632cce46d942af68356b38\"" } ], "miniaturaSubida": true }
← 200 { "archivoId": "…", "estado": "COMPLETADO" }
```

- `partes` solo en multipart, con todas las partes en orden.
- Si `miniaturaSubida` es `true` y la miniatura está en S3 con ≤ 1 MB, se asocia; si no, el
  archivo queda sin miniatura (nunca falla por la miniatura).
- Errores: `RECEPCION_CERRADA` (objeto borrado o multipart abortado), `SUBIDA_INCOMPLETA` (puede
  reintentarse), `CONTENIDO_INCONSISTENTE` (objeto borrado), `NO_ENCONTRADO`.

### `DELETE /api/subidas/{archivoId}`

El invitado quita un archivo pendiente de su lista. Aborta el multipart o borra el objeto simple.
`204`. Un archivo `COMPLETADO` no se puede borrar desde aquí (`404 NO_ENCONTRADO`).

---

## API de administración (novios)

Todos los endpoints salvo el login exigen `Authorization: Bearer <token>`. Sin token válido →
`401 NO_AUTENTICADO`.

### `POST /api/admin/login`

```json
→ { "usuario": "mara", "contrasena": "••••••••" }
← 200 { "token": "q8Jx…43 caracteres…", "usuario": "mara", "nombreVisible": "Mara", "inactividadMaxSegundos": 28800 }
```

Fallo, usuario inexistente o cuenta bloqueada → `401 CREDENCIALES_INVALIDAS`, siempre con el mismo
mensaje y un tiempo de respuesta similar (FR-025).

### `POST /api/admin/logout`

Invalida el token (FR-030). `204`.

### `GET /api/admin/resumen`

Contador y estado para la cabecera de la galería (FR-030b, banner de fecha límite). Funciona
también después del fin de disponibilidad.

```json
← 200 {
    "archivosTotal": 1240, "fotos": 1102, "videos": 138,
    "ultimaSubidaEn": "2026-11-22T02:41:10Z",
    "recepcion": { "estado": "ABIERTA", "cierreEn": "2026-11-22T17:00:00Z" },
    "disponibleHasta": "2026-12-02T04:59:59Z",
    "periodoTerminado": false
  }
```

### `GET /api/admin/galeria?cursor=<opaco>&limite=24`

Página de la galería, del más reciente al más antiguo (FR-026). `limite` va de 1 a 48 (24 por
defecto).

```json
← 200 {
    "elementos": [
      {
        "archivoId": "…", "tipo": "VIDEO", "formato": "mov",
        "nombreInvitado": "Tía Carmen", "nombreOriginal": "IMG_2041.MOV",
        "tamano": 312475648, "subidoEn": "2026-11-21T23:12:44Z",
        "urlMiniatura": "https://…miniaturas/….jpg?X-Amz-…"
      }
    ],
    "siguienteCursor": "eyJn…"
  }
```

- `urlMiniatura` es `null` si no hay miniatura. El front muestra entonces el ícono del tipo; para
  JPG, PNG y WEBP sin miniatura usa el original en modo diferido (research R4).
- `siguienteCursor` es `null` en la última página.
- Después del fin de disponibilidad → `410 PERIODO_TERMINADO`.

### `GET /api/admin/archivos/{archivoId}/url?uso=ver|descargar`

URL prefirmada de 15 minutos para el original (FR-027). `descargar` fuerza
`Content-Disposition: attachment` con el nombre original.

```json
← 200 { "url": "https://…", "expiraEn": "…" }
```

Errores: `NO_ENCONTRADO` (por ejemplo, si el otro novio lo eliminó), `PERIODO_TERMINADO`.

### `DELETE /api/admin/archivos/{archivoId}`

Elimina definitivamente el original y la miniatura, y actualiza los contadores (FR-028). La
confirmación la pide el front. `204`. Errores: `NO_ENCONTRADO`.

### `POST /api/admin/recepcion/cerrar`

Cierre anticipado (FR-029). Persiste en `boda-evento`, efecto inmediato e idempotente.

```json
← 200 { "estado": "CERRADA", "cierreEn": "2026-11-21T04:02:00Z", "cerradaPor": "daniel" }
```

---

## Línea de comandos (mismo `.jar`, sin servidor web)

```text
java -jar app.jar hash-contrasena
```

Pide la contraseña dos veces, sin eco, y escribe el hash bcrypt en la salida estándar. No toca AWS.
Uso en el procedimiento de cuentas (plan, sección "Recursos AWS").
