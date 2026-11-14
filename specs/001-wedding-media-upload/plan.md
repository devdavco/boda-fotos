# Implementation Plan: Fotos y videos de la boda vía QR

**Branch**: `david/001-plan` | **Date**: 2026-11-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-wedding-media-upload/spec.md`

**Note**: plan generado con el flujo `/speckit-plan`. El script `setup-plan.ps1` no se ejecutó
(no hay PowerShell en la máquina); se hizo a mano lo mismo: copiar la plantilla a `plan.md` en la
carpeta del feature.

## Summary

Los invitados abren con el QR una app Angular en Amplify, escriben su nombre y suben fotos y
videos **directo a S3** con URLs prefirmadas de 5 minutos. Las fotos y videos < 100 MB van con un
`PUT`; los videos ≥ 100 MB, en multipart con partes de 10 MB, reintento por parte y una URL
pedida por parte justo antes de enviarla (ninguna vence esperando). Un backend
Spring Boot (un `.jar` en una EC2 `t3.micro` detrás de `boda-alb` y `boda-cf`) es la autoridad:
- valida nombre, tipo, tamaño, tope por sesión y ventana de recepción antes de firmar;
- verifica tamaño y firma del formato al completar;
- guarda los metadatos en DynamoDB.

Los novios usan otra app Angular en Amplify con usuario y contraseña (bcrypt, bloqueo tras 5
fallos y token opaco con 8 h de inactividad). Ven una galería paginada desde un GSI disperso,
descargan con URLs prefirmadas, eliminan y cierran la recepción antes de tiempo.

La infraestructura existente de [`infra/README.md`](../../infra/README.md) se reutiliza tal cual.
Lo nuevo son tres tablas, un GSI, un bucket de artefactos, la política IAM ampliada, una versión
nueva de la launch template, el Auto Scaling group y dos apps de Amplify (ver "Recursos AWS").

## Technical Context

**Language/Version**: Java 25 (Corretto 25) en el backend; TypeScript con Angular 21 en los
front-ends.

**Primary Dependencies**:
- Backend: Spring Boot 4.1.x (`web`, `security`, `validation`, `actuator`), AWS SDK for Java v2
  (`s3`, `dynamodb`), Maven.
- Front-ends: Angular 21 (`HttpClient`, router), sin librerías de UI.
- Detalle en [research.md](research.md) R12 y R14.

**Storage**: S3 `boda-fotos-2026` (archivos, prefijo `eventos/boda-2026/`) y DynamoDB on-demand:
`boda-fotos` (existente, con GSI nuevo), `boda-evento`, `boda-admins` y `boda-sesiones` (nuevas).
Ver [data-model.md](data-model.md).

**Testing**: verificación manual con la lista de chequeo de [quickstart.md](quickstart.md) §4 en
celulares reales y en producción (obligatoria). Pruebas unitarias JUnit 5 solo de la lógica pura
del backend (ventana y zona horaria, validaciones, expiración de URLs). Sin pruebas automáticas
en los front-ends.

**Target Platform**:
- Backend: Amazon Linux 2023 en EC2 `t3.micro` (1 GB de RAM), `us-east-1`.
- Front-ends: versiones recientes de Safari iOS, Chrome Android y Samsung Internet. El front de
  novios también se usa en navegadores de escritorio.

**Project Type**: aplicación web, con un API y dos SPA estáticas.

**Performance Goals**:
- 50 invitados subiendo a la vez (SC-004).
- Primera foto subida en menos de 1 min desde el escaneo (SC-001).
- Foto ≤ 10 MB en menos de 15 s en 4G (SC-003). Depende sobre todo del ancho de subida del
  celular; el backend solo firma.
- Bloque de galería en menos de 3 s (SC-007).

**Constraints**:
- Fecha límite 2026-11-18 con la verificación hecha.
- Los archivos nunca pasan por el backend.
- URLs de subida de ≤ 5 min (y nunca más allá del cierre).
- Infraestructura por consola, en `us-east-1`, sin dominio propio.
- Sin pipeline de CI/CD para el backend (Amplify ya construye los front-ends).

**Scale/Scope**: 100–300 invitados, 2.000–5.000 archivos (~80 GB en el escenario alto), 2 cuentas
admin, un evento.

No quedan puntos `NEEDS CLARIFICATION`: todos se resolvieron en [research.md](research.md). La
miniatura de fotos en el celular se aprobó el 2026-11-14 y ya está en la spec.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Cómo lo cumple el plan | Estado |
| --- | --- | --- |
| **I. La fecha manda** | Componentes mínimos: un `.jar`, dos SPA, tablas on-demand. Sin servicios nuevos fuera del stack (Auto Scaling y SNS/CloudWatch ya estaban previstos en `infra/README.md`). Sin CI/CD para el backend (R10). Lo fuera de alcance (ZIP, filtros, etc.) no se implementa. Lo que puede no caber en el plazo está señalado en "Calendario y alcance". Excepción aprobada para publicar el front de novios hasta el 2026-11-22 12:00, registrada en "Complexity Tracking". | ✅ con excepción |
| **II. Privado y seguro** | Bucket privado con SSE-S3; todo acceso con URLs prefirmadas (5 min subida, 15 min lectura). HTTPS por los dominios de Amplify y CloudFront; CloudFront → ALB por HTTP y el ALB solo acepta el prefix list de CloudFront (ya configurado). bcrypt costo 12. Bloqueo tras 5 fallos y mensaje genérico. IAM limitado al prefijo `eventos/boda-2026/*` y a tablas y acciones concretas, sin `Scan`. Sin secretos en el repo ni en SSM, porque el diseño no los necesita (R11). | ✅ |
| **III. Servidor autoridad** | La ventana, el cierre anticipado y los topes se evalúan en el backend con `America/Bogota`. Las fechas son configuración en `boda-evento` y el cierre anticipado persiste ahí. Marcas de tiempo de archivos en UTC. Tipo y tamaño se validan antes de firmar y se verifican en S3 al completar. Las claves las genera el sistema (UUID). URLs que expiran como máximo al cierre; la finalización se rechaza después del cierre. | ✅ |
| **IV. Móvil primero** | Subida directa navegador → S3, progreso por archivo, reintento solo de fallidos, multipart ≥ 100 MB con reintento por parte. Flujo de 4 pasos: QR, nombre, elegir, subir. | ✅ |
| **V. Infraestructura documentada y costo** | Todo por consola en `us-east-1`; cada recurso nuevo está listado abajo para crearlo y documentarlo en `infra/README.md` antes de usarlo. La lifecycle que aborta multipart ya existe; retención explícita según FR-033. S3 Standard, DynamoDB on-demand, una `t3.micro` detrás del ALB; sin NAT, sin Transfer Acceleration, sin cómputo multi-AZ (el ASG tiene 1 instancia). Estimación de costos en [research.md](research.md) R16. Apagado del cómputo el 2026-12-02. | ✅ |
| **VI. Español** | Interfaz y catálogo de errores en español ([api.md](contracts/api.md), [ui.md](contracts/ui.md)). Aviso de uso de las fotos. El nombre se presenta como declarado. | ✅ |
| **Flujo y calidad** | Lista de chequeo manual con todos los mínimos de la constitución, en producción el 17–18 de noviembre ([quickstart.md](quickstart.md) §4). Congelamiento del 19 al 22. Los cambios de infraestructura se documentan antes de aplicarlos. | ✅ |

**Resultado previo a Phase 0**: pasa. La única excepción (fecha del front de novios) está aprobada
y justificada en "Complexity Tracking".

**Re-evaluación después de Phase 1** (diseño en data-model y contratos): pasa, con dos notas que
no son violaciones:
- El cierre anticipado no puede revocar URLs ya emitidas; siguen sirviendo hasta 5 minutos. Las
  finalizaciones se rechazan y el objeto se borra, así que nada entra a la galería después del
  cierre (FR-015 en la práctica; ver R2 y R9).
- La alarma de 5xx mide todo el API, no solo las autorizaciones de subida (R15).

## Project Structure

### Documentation (this feature)

```text
specs/001-wedding-media-upload/
├── spec.md
├── plan.md              # Este archivo
├── research.md          # Phase 0: decisiones y estimación de costos
├── data-model.md        # Phase 1: tablas, atributos, estados
├── quickstart.md        # Phase 1: cómo correr y lista de chequeo manual
├── contracts/
│   ├── api.md           # Phase 1: endpoints, errores, línea de comandos
│   └── ui.md            # Phase 1: pantallas y textos de los dos front-ends
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; no lo crea este comando)
```

### Source Code (repository root)

```text
backend/                          # Spring Boot 4.1, Java 25, Maven → target/app.jar
├── pom.xml
└── src/
    ├── main/java/co/boda/fotos/
    │   ├── BodaApplication.java  # arranque web o modo línea de comandos (hash-contrasena)
    │   ├── config/               # propiedades (variables de entorno), clientes del SDK, seguridad
    │   ├── recepcion/            # evento, estado de la ventana, cierre anticipado
    │   ├── subida/               # sesiones de subida, autorizar, partes, completar, validaciones
    │   ├── galeria/              # resumen, listado paginado, URLs de lectura, eliminar
    │   ├── admin/                # login, bloqueo, sesiones de admin, filtro Bearer
    │   └── error/                # códigos y mensajes en español (contracts/api.md)
    ├── main/resources/application.yml
    └── test/java/co/boda/fotos/  # JUnit 5: ventana, validaciones, expiraciones
front-invitados/                  # Angular 21, una pantalla (contracts/ui.md)
├── package.json
├── proxy.conf.json               # /api → localhost:8080 en desarrollo
└── src/app/
    ├── estado/                   # estado de la recepción
    ├── subida/                   # cola, simple, multipart, reintentos, miniaturas
    └── api/                      # cliente del API de invitados
front-novios/                     # Angular 21: entrar, galería, archivo (contracts/ui.md)
├── package.json
├── proxy.conf.json
└── src/app/
    ├── auth/                     # login, interceptor Bearer, guard
    ├── galeria/                  # cabecera con contador, banner, cuadrícula infinita
    └── archivo/                  # vista completa, descargar, eliminar
amplify.yml                       # monorepo: applications → front-invitados, front-novios
infra/README.md                   # inventario y pasos de consola (lo actualizas tú)
```

**Structure Decision**: monorepo con una carpeta por pieza desplegable, como pide la constitución:
`backend/` va a EC2 por el bucket de artefactos, y `front-invitados/` y `front-novios/` a dos apps
de Amplify separadas. La infraestructura no tiene código; su "fuente" es `infra/README.md`. El
paquete Java `co.boda.fotos` es una propuesta y puede cambiarse.

## Recursos AWS nuevos o modificados

Todo en la cuenta `248033171214`, `us-east-1`, con el tag `proyecto=boda`. **Para que los crees en
la consola y los documentes en `infra/README.md`**, en este orden. Los marcados ✱ son
modificaciones de recursos existentes.

| # | Recurso | Nombre | Configuración clave |
| --- | --- | --- | --- |
| 1 | Tabla DynamoDB | `boda-evento` | PK `eventoId` (S). On-demand, Standard, cifrado *Owned by Amazon*, deletion protection, PITR. |
| 2 | Tabla DynamoDB | `boda-admins` | PK `usuario` (S). Igual que la anterior, con PITR. |
| 3 | Tabla DynamoDB | `boda-sesiones` | PK `id` (S). Igual, **TTL** sobre `expiraEnEpoch`, sin PITR (datos efímeros). |
| 4 ✱ | GSI en `boda-fotos` | `galeria-index` | PK `galeriaEvento` (S), SK `galeriaOrden` (S), proyección *All*. Activar también PITR en la tabla (ya estaba pendiente). |
| 5 | Bucket S3 | `boda-artefactos-248033171214` | Privado (Block all public access), ACLs disabled, SSE-S3, **versioning activado** (permite volver a la versión anterior). Guarda `backend/app.jar`. |
| 6 ✱ | Política IAM | `boda-backend-policy` | Reemplazar por el JSON de abajo (quita `Scan` y amplía S3 y tablas). |
| 7 | **Paso, no recurso**: `.jar` mínimo | `s3://boda-artefactos-248033171214/backend/app.jar` | Subir un `.jar` mínimo (esqueleto del backend: `/actuator/health` y un `/api/recepcion/estado` con respuesta fija para el spike de R1), construido con `mvn package` y copiado con `aws s3 cp`. **Debe existir antes de crear la launch template v3 y el ASG**: la user data hace `aws s3 cp` con `set -e`, y si el objeto no está, el servicio no arranca, la instancia nunca queda sana y el ASG la reemplaza en bucle. |
| 8 ✱ | Launch template | `boda-backend-lt` v3 (por defecto) | Igual que v2, con la user data de [research.md](research.md) R10 (descarga el `.jar` y lo arranca con `systemd`). Se crea después del paso 7. Necesita el rol con la política del paso 6 ya reemplazada. |
| 9 | Auto Scaling group | `boda-backend-asg` | **Se crea solo cuando el paso 7 está hecho y la v3 es la versión por defecto.** LT `boda-backend-lt` (Default). Subredes `us-east-1a` y `us-east-1b` de la VPC por defecto. Asociado a `boda-backend-tg`. Health check ELB, gracia 300 s. Min/deseado/max = 1. Instance maintenance policy: *launch before terminating* (mínimo 100 %, máximo 200 %). Sin políticas de escalado. Tags propagados. |
| 10 ✱ | Alarmas | `boda-backend-sin-salud`, `boda-api-errores-5xx` | Activar acciones cuando el backend esté sano (comando en `infra/README.md` §12). |
| 11 | Amplify | `boda-invitados` | Repo `devdavco/boda-fotos`, **rama `david/release`** (debe existir antes en `origin`, con `amplify.yml` y el esqueleto de `front-invitados` ya mergeados), monorepo `front-invitados`, rewrites de [research.md](research.md) R12. Anotar el dominio: es la **URL del QR**. Amplify reemplaza `/` por `-` en el subdominio de la rama, así que será del tipo `https://david-release.<appId>.amplifyapp.com`; confirmar el dominio exacto al conectar la rama. |
| 12 | Amplify | `boda-novios` | Igual, **rama `david/release`**, monorepo `front-novios`. Si se crea antes de que exista la carpeta `front-novios/` en esa rama, su primer build falla; es esperado y se resuelve con el primer merge del esqueleto. |
| 13 ✱ | CORS de `boda-fotos-2026` | — | `AllowedOrigins`: dominio de `boda-invitados` y `http://localhost:4200` (quitar antes del 19). `AllowedMethods`: `["PUT"]`. `ExposeHeaders`: `["ETag"]` (se mantiene: el navegador lee el `ETag` de cada parte del multipart para completarlo). Se quita el dominio de CloudFront: ninguna página se sirve desde ahí. **El dominio de `boda-novios` no se agrega**: ese front no usa `fetch` ni XHR contra S3; muestra con `<img>` y `<video>` y descarga con un enlace directo a la URL prefirmada con `Content-Disposition: attachment`, y nada de eso necesita CORS. Si algún día descarga con `fetch` (por ejemplo, para un ZIP), hay que agregar `GET` para su dominio. |
| 14 | Ítem DynamoDB | `boda-evento` / `boda-2026` | Comando abajo. Lo necesita el backend completo, no el `.jar` mínimo. |
| 15 | Ítems DynamoDB | `boda-admins` / `mara`, `daniel` | Procedimiento abajo. |

Dependencias entre pasos: **bucket de artefactos (5) → política IAM (6) → `.jar` mínimo subido (7) →
launch template v3 (8) → ASG (9)**. La política va antes del ASG porque la instancia descarga el
`.jar` con el rol `boda-ec2-role`. El `.jar` mínimo se reemplaza después por el backend completo
con un *instance refresh* (research R10).

**No se crean**: parámetros de SSM (no hay secretos, R11), NAT Gateway, certificados ACM, pipeline
de CI/CD, Lambda.

**Política `boda-backend-policy`** (reemplaza la actual):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "S3MediosEvento",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload"],
      "Resource": "arn:aws:s3:::boda-fotos-2026/eventos/boda-2026/*"
    },
    {
      "Sid": "S3Artefactos",
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": "arn:aws:s3:::boda-artefactos-248033171214/backend/*"
    },
    {
      "Sid": "DynamoFotos",
      "Effect": "Allow",
      "Action": ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query"],
      "Resource": [
        "arn:aws:dynamodb:us-east-1:248033171214:table/boda-fotos",
        "arn:aws:dynamodb:us-east-1:248033171214:table/boda-fotos/index/galeria-index"
      ]
    },
    {
      "Sid": "DynamoEventoYAdmins",
      "Effect": "Allow",
      "Action": ["dynamodb:GetItem", "dynamodb:UpdateItem"],
      "Resource": [
        "arn:aws:dynamodb:us-east-1:248033171214:table/boda-evento",
        "arn:aws:dynamodb:us-east-1:248033171214:table/boda-admins"
      ]
    },
    {
      "Sid": "DynamoSesiones",
      "Effect": "Allow",
      "Action": ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem"],
      "Resource": "arn:aws:dynamodb:us-east-1:248033171214:table/boda-sesiones"
    }
  ]
}
```

Revisión de acciones contra el diseño. Cada operación que hace el backend o que firma para el
navegador está cubierta, y no sobra ninguna:

| Operación del diseño | Acción IAM |
| --- | --- |
| `PUT` simple prefirmado (original y miniatura) | `s3:PutObject` |
| `CreateMultipartUpload`, `UploadPart` prefirmado, `CompleteMultipartUpload` | `s3:PutObject` |
| `HeadObject` y `GetObject` con `Range` al completar; `GET` prefirmado de galería, vista y descarga | `s3:GetObject` |
| Borrar un objeto que no coincide o llegó tras el cierre, cancelación del invitado, eliminación de los novios (original y miniatura) | `s3:DeleteObject` |
| Abortar el multipart rechazado o cancelado | `s3:AbortMultipartUpload` |
| Descargar el `.jar` al arrancar | `s3:GetObject` en `boda-artefactos-…/backend/*` |
| Autorizar (crear ítem), completar, descartar y eliminar archivos (con transacción) | `dynamodb:PutItem`, `dynamodb:UpdateItem` en `boda-fotos` |
| Leer un archivo; galería paginada | `dynamodb:GetItem` en `boda-fotos`; `dynamodb:Query` en `galeria-index` |
| Leer la ventana; contadores y cierre anticipado | `dynamodb:GetItem`, `dynamodb:UpdateItem` en `boda-evento` |
| Login, intentos fallidos y bloqueo | `dynamodb:GetItem`, `dynamodb:UpdateItem` en `boda-admins` |
| Sesiones de subida y de admin: crear, leer, contar, renovar y cerrar | `dynamodb:PutItem`, `GetItem`, `UpdateItem`, `DeleteItem` en `boda-sesiones` |

- `s3:PutObject` cubre `CreateMultipartUpload`, `UploadPart` y `CompleteMultipartUpload`, y
  `s3:GetObject` cubre `HeadObject`.
- Las transacciones (`TransactWriteItems`) se autorizan con el `UpdateItem` de cada tabla.
- No se usa `s3:ListMultipartUploadParts`: el navegador guarda los `ETag` de las partes y los envía
  al completar. Tampoco `s3:ListBucket` (R2), `dynamodb:Scan` ni KMS (el cifrado es SSE-S3).
- **`ConditionCheck`**: ninguna `TransactWriteItems` del diseño lo usa. Las dos transacciones
  (completar y eliminar un archivo, [data-model.md](data-model.md) §2) son pares de `Update`: uno
  sobre el ítem del archivo, con su condición (`estado = PENDIENTE` o `COMPLETADO`) dentro de la
  propia `Update`, y otro sobre el ítem del evento para los contadores. Eso se autoriza con
  `dynamodb:UpdateItem` en cada tabla; `dynamodb:ConditionCheckItem` solo hace falta cuando una
  transacción incluye una operación `ConditionCheck` aparte, y por eso no se agrega. Si el diseño
  cambia y se añade una, hay que sumar `dynamodb:ConditionCheckItem` a la tabla de esa operación.
- La creación de cuentas y del ítem del evento se hace desde CloudShell con tu usuario, no con el
  rol.
- El rol sigue teniendo `AmazonSSMManagedInstanceCore` para Session Manager.

**Ítem del evento** (CloudShell):

```bash
aws dynamodb put-item --table-name boda-evento \
  --condition-expression "attribute_not_exists(eventoId)" \
  --item '{
    "eventoId": {"S": "boda-2026"},
    "nombre": {"S": "Boda de Mara y Daniel"},
    "fechaBoda": {"S": "2026-11-21"},
    "zonaHoraria": {"S": "America/Bogota"},
    "aperturaLocal": {"S": "2026-11-19T00:00"},
    "cierreLocal": {"S": "2026-11-22T12:00"},
    "disponibleHastaLocal": {"S": "2026-12-01T23:59:59"},
    "maxArchivosPorSesion": {"N": "300"},
    "maxBytesPorSesion": {"N": "5368709120"},
    "archivosTotal": {"N": "0"},
    "fotos": {"N": "0"},
    "videos": {"N": "0"}
  }'
```

**Cuentas de los novios** (FR-031; research R7). El hash se genera en tu equipo y el ítem se
escribe desde CloudShell:

```bash
# En tu equipo, con el .jar construido; pide la contraseña sin eco
java -jar backend/target/app.jar hash-contrasena

# En CloudShell (crear). Repetir con "daniel" / "Daniel".
aws dynamodb put-item --table-name boda-admins \
  --condition-expression "attribute_not_exists(usuario)" \
  --item '{"usuario":{"S":"mara"},"nombreVisible":{"S":"Mara"},"hashContrasena":{"S":"<hash>"},"intentosFallidos":{"N":"0"}}'

# Restablecer contraseña o desbloquear
aws dynamodb update-item --table-name boda-admins --key '{"usuario":{"S":"mara"}}' \
  --update-expression "SET hashContrasena = :h, intentosFallidos = :z REMOVE bloqueadaHasta" \
  --expression-attribute-values '{":h":{"S":"<hash>"},":z":{"N":"0"}}'
```

Las contraseñas se entregan a Mara y Daniel por un canal privado y nunca se escriben en el repo.

## Ramas y despliegue de los front-ends

- Las dos apps de Amplify (`boda-invitados` y `boda-novios`) se conectan a la rama
  **`david/release`**, no a `main`. Cada push a esa rama dispara el despliegue.
- Flujo: se trabaja en ramas `david/...`; solo cuando algo está listo (probado en local y, si
  aplica, en el celular) se hace merge a `david/release`. Eso es lo que llega a producción.
- Del 19 al 22 de noviembre solo entran a `david/release` correcciones críticas (las que impiden
  subir o consultar archivos), más la excepción del front de novios (ver "Complexity Tracking").
- `main` y el resto de las ramas no despliegan nada. El backend tampoco depende de la rama: se
  construye en local y se publica como `.jar` en el bucket de artefactos (research R10).
- La rama `david/release` **no existe todavía en `origin`**. Crearla antes de conectar Amplify.
- El dominio de Amplify depende del nombre de la rama: no renombrarla ni desconectarla (riesgo K6).

## Calendario y alcance

Hoy es sábado 2026-11-14. Quedan unos **4 días y medio** hasta el miércoles 18. El trabajo
estimado (backend ~1,5 días, front de invitados ~1,5, front de novios ~1, infraestructura ~0,5 y
verificación ~1) suma **unos 5,5 días**: **no cabe con holgura**. El orden de abajo pone primero
lo que no se puede perder.

| Día | Objetivo | Criterio de salida |
| --- | --- | --- |
| Sáb 14 (tarde) | En este orden: (1) tablas y GSI (1–4) y bucket de artefactos (5); (2) política IAM (6); (3) **`.jar` mínimo con `/actuator/health` subido al bucket** (7); (4) launch template v3 (8); (5) **ASG (9), solo con el `.jar` ya en el bucket**; (6) rama `david/release` con `amplify.yml` y el esqueleto de `front-invitados`, y las apps de Amplify (11 y 12) y el CORS (13); (7) ítem del evento (14). **Spike** de R1 (proxy de Amplify) y R2 (PUT prefirmado desde un iPhone). | `boda-backend-tg` con 1 instancia *healthy*. Desde el iPhone, la página de Amplify muestra el estado y sube una foto a S3. |
| Dom 15 | Backend de invitados completo (sesiones, autorizar, partes, completar, validaciones, tope). Front de invitados con subida simple, progreso y reintento. | Fotos de 2 celulares reales en `boda-fotos` como `COMPLETADO`. |
| Lun 16 | Multipart en el front. Miniaturas en el celular. Backend de admin (login, bloqueo, resumen, galería, URLs, eliminar, cerrar). Cuentas de Mara y Daniel. | Video de 300 MB por red móvil con corte y reanudación. Login por `curl`. |
| Mar 17 | Front de novios. Activar alarmas. Primera pasada de la lista de chequeo §4. | Galería navegable en celular y escritorio. |
| Mié 18 | Lista de chequeo §4 completa en producción, correcciones, restaurar (§4.9), validar el QR. | SC-008 cumplido. |

**No se recorta** (P1 y constitución): flujo de invitados, ventana y cierre, multipart con
reintento, validaciones en el servidor, login seguro, galería con descarga.

**Si hay retraso, se recorta en este orden** (todo dentro del alcance de la spec, así que cada
recorte deja algo sin cumplir y lo señalo):
1. **Prueba en Samsung Internet**, si no hay un equipo disponible (FR-002 queda verificado solo en
   Chrome y Safari).
2. **Pruebas unitarias del backend**: son opcionales según la constitución.

Las miniaturas generadas en el celular (fotos y videos) **no se recortan**: forman parte de la
spec desde el 2026-11-14.

**Excepción aprobada para el front de novios**: si no está listo el 18, puede publicarse durante
el congelamiento, **como máximo antes del 2026-11-22 a las 12:00**. Condiciones y justificación en
"Complexity Tracking". El backend completo, incluido el API de administración, sigue con fecha
2026-11-18.

## Desviaciones de la spec

1. **Miniaturas de fotos generadas en el celular**: aprobada el 2026-11-14 e **incorporada a la
   spec** (Clarifications, FR-026, FR-026a, Key Entities y la suposición sobre miniaturas). Ya no
   es una desviación; queda aquí como registro ([research.md](research.md) R4).
2. **Finalización rechazada tras el cierre en ambos modos**. FR-016 solo lo exige para multipart;
   el plan lo aplica también a subidas simples para tener una sola regla y cumplir el escenario 2
   de la US4. Una foto que termine de subir segundos después del cierre del domingo 22 no entra.
3. **Contador de la galería** (FR-030b): cuenta los archivos visibles (completados menos
   eliminados), para que coincida con la cuadrícula.
4. **Alarma de errores** (FR-030a): aproximada con los 5xx de todo el API (R15).

## Riesgos

| # | Riesgo | Impacto | Mitigación |
| --- | --- | --- | --- |
| K1 | El plazo no alcanza (5,5 días de trabajo en 4,5). | Se llega al 18 sin todo verificado. | Orden de prioridad y lista de recortes de arriba; spike el primer día; la verificación del 18 no se recorta; excepción aprobada para el front de novios (hasta el 22 a las 12:00). |
| K2 | El rewrite de Amplify no reenvía bien métodos, cuerpo o encabezados. | Los front-ends no hablan con el API. | Se verifica el sábado 14. Plan B: llamar directo a CloudFront con CORS en Spring; la autenticación con Bearer sigue funcionando (R1, R6). |
| K3 | Comportamiento real de los celulares: Safari suspende en segundo plano, `file.type` vacío en HEIC/MOV, generación de miniaturas inestable en iOS. | Subidas fallidas o rechazos falsos. | Tipo por extensión, reintento, aviso de mantener la pantalla abierta, miniatura con tiempo máximo y sin bloquear la subida. Pruebas en celulares reales desde el día 1 (R13). |
| K4 | Una sola instancia del backend. | Si cae durante la boda, nadie puede autorizar subidas por unos 5 min mientras el ASG la reemplaza. | Las subidas ya autorizadas siguen yendo a S3. La alarma avisa en < 10 min (SC-010). Health check solo de liveness. La constitución no permite cómputo multi-AZ sin justificación. |
| K5 | Olvidar restaurar la ventana real o borrar los datos de prueba tras la verificación del 17–18. | La recepción abre antes de tiempo o la galería empieza con basura. | Paso obligatorio §4.9 del quickstart, revisado el 18 por la noche. |
| K6 | Recrear o borrar la app de Amplify de invitados, o renombrar o desconectar la rama `david/release`, cambia su dominio (el subdominio sale del nombre de la rama). | El QR impreso deja de funcionar. | Crear la app y la rama el sábado 14, no borrarlas ni renombrarlas nunca e imprimir el QR solo después de §4.9. |
| K7 | Un merge a `david/release` durante el congelamiento despliega solo, y esa rama alimenta las dos apps. | Cambio no probado en plena boda, o un front de novios que vuelve a desplegar el de invitados. | Trabajar en ramas `david/...` y hacer merge a `david/release` solo cuando algo está listo; del 19 al 22 solo correcciones críticas y el front de novios de la excepción; desactivar el *auto build* de `boda-invitados` durante esos días (ver condiciones de la excepción). |
| K8 | Una subida simple llega a S3 pero su finalización no (el navegador se cerró justo al terminar). | Un archivo que los novios no ven. | El front reintenta la finalización. Revisión manual de huérfanos después del cierre (quickstart §5). |
| K9 | Si S3 no firma el `Content-Length`, alguien puede subir más bytes de los declarados con una URL válida durante 5 min. | Almacenamiento extra (nunca entra a la galería). | Verificación con `HeadObject` al completar y borrado; tope por sesión; se comprueba en el spike (R2). |
| K10 | Costo: el ALB y las IPv4 públicas dominan (~17 USD); descargas por encima de 100 GB/mes. | Pasar el presupuesto de USD 30/mes. | Estimación en R16 (~26–32 USD en el periodo, repartidos en 2 meses). Apagar el cómputo el 2026-12-02. El aviso del presupuesto sigue activo. |
| K11 | Precios de la estimación sin verificar hoy. | Desvío en la estimación. | Revisar R16 con AWS Pricing Calculator antes de desplegar. |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **Excepción al Principio I** ("sistema completo desplegado y verificado el 2026-11-18") **y a la regla de congelamiento** ("del 19 al 22 solo correcciones de defectos que impidan subir o consultar"), solo para `front-novios`. Aprobada el 2026-11-14. **Fecha límite: publicado y verificado antes del 2026-11-22 a las 12:00.** | El trabajo estimado (~5,5 días) supera el tiempo disponible (~4,5). El front de novios es P2/P3 en la spec y los archivos quedan seguros en S3 mientras tanto; el front de invitados es P1 y no puede retrasarse. La fecha límite garantiza que los novios puedan usar el cierre anticipado y ver la galería antes de que cierre la recepción. | Recortar funciones del front de invitados o de seguridad (multipart, validaciones, login) pone en riesgo el evento o la constitución. Esperar a publicar después del 22 dejaría a los novios sin cierre anticipado durante toda la recepción. |

Condiciones de la excepción:
- Cubre **solo** `front-novios`. El backend completo (también el API de administración), el front
  de invitados y la infraestructura se despliegan y verifican antes del 2026-11-18, como exige la
  constitución. Publicar el front de novios no toca nada del camino de los invitados.
- El front de novios se desarrolla en una rama `david/...` y entra a `david/release` solo cuando
  está listo. Del 19 al 22, desactivar el *auto build* de la rama `david/release` en la app
  `boda-invitados` de Amplify, para que ese merge no vuelva a desplegar el front de invitados. Se
  reactiva solo para una corrección crítica del front de invitados y después del 22.
- Antes de publicarlo: login, galería, descarga, eliminar y cerrar la recepción ya están
  verificados por `curl` contra producción el 18 (quickstart §4.5 y §4.6). Al publicarlo se repiten
  §4.5 y §4.6 desde la interfaz, en celular y escritorio.
- Mientras no esté publicado, el cierre anticipado lo hace el responsable técnico a pedido de los
  novios, escribiendo `cierreAnticipadoEn` y `cierreAnticipadoPor` en el ítem `boda-2026` de
  `boda-evento` desde la consola (mismo efecto que el botón: el backend lo lee en cada petición).
- La constitución no se modifica; la excepción vive solo en este plan.

Lo agregado sobre la infraestructura existente (Auto Scaling, bucket de artefactos, tablas y GSI)
ya estaba previsto en `infra/README.md` o en el stack de la constitución, así que no es una
violación.
