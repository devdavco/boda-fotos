# Research: Fotos y videos de la boda vía QR

**Feature**: `001-wedding-media-upload` | **Fecha**: 2026-11-14 | **Plan**: [plan.md](plan.md)

Cada sección registra una decisión con su motivo y las alternativas descartadas. Las restricciones
de partida son la constitución v2.0.0 y la infraestructura ya creada en
[`infra/README.md`](../../infra/README.md).

---

## R1. Cómo llega el API a los front-ends

- **Decision**: cada app de Amplify tiene el rewrite `/api/<*>` →
  `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` (200, reverse proxy). Los front-ends llaman a
  `/api/...` con rutas relativas. El backend no configura CORS.
- **Rationale**: el navegador ve un único origen (el dominio de Amplify), así que no hay CORS ni
  preflight hacia el API. Coincide con el contrato técnico ya acordado.
- **Alternatives considered**: llamar directo a CloudFront con CORS. Funciona, pero agrega
  preflights en cada llamada y configuración de CORS en Spring. Queda como **plan B** si el
  rewrite no se comporta bien (ver R2 y riesgo K2 del plan): cambiar la URL base del API en los
  front-ends y habilitar CORS en Spring para los dos dominios de Amplify. Como la autenticación usa
  un encabezado `Authorization` (R6) y no cookies, el plan B no rompe el login.
- **Verificado el 2026-11-14** (spike de R1, T023, con el backend mínimo, a través de
  `https://david-release.d2x2ckep95pz3x.amplifyapp.com`):
  - `GET /api/recepcion/estado`: 200, el mismo JSON que por CloudFront.
  - `POST` y `DELETE` al mismo path: 405 generado por Spring, es decir, ambos métodos atraviesan
    el rewrite y llegan al backend.
  - Una ruta de Angular (que no es un archivo): 200 con `index.html`, así que la regla de SPA
    funciona.
  - Plan B **no** activado.
- **Pendiente** (T052, con los endpoints reales): que el proxy reenvíe el cuerpo JSON y los
  encabezados `Authorization` y `X-Sesion-Subida`, y que devuelva los códigos de error del backend
  sin reescribirlos. Si algo falla, se activa el plan B.

## R2. Cómo se transfieren los archivos

- **Decision**: subida y descarga directas entre el navegador y el endpoint regional de S3
  (`boda-fotos-2026.s3.us-east-1.amazonaws.com`) con URLs prefirmadas por el backend (AWS SDK v2,
  `S3Presigner`).
  - Fotos y videos < 100 MB: un `PUT` prefirmado.
  - Videos ≥ 100 MB: multipart. El backend crea la carga (`CreateMultipartUpload`) y la completa
    (`CompleteMultipartUpload`). Partes de 10 MB (500 MB = 50 partes), hasta 3 partes en paralelo,
    reintento por parte.
  - **Las URLs de las partes se piden una por parte, justo antes de enviarla**, nunca todas al
    inicio. Cada petición a `/partes` lleva como máximo 3 números (los que van a salir en
    paralelo). Si el `PUT` de una parte responde 403 porque la URL venció (por ejemplo, tras una
    pausa de la red o con la pantalla bloqueada), el navegador pide una URL nueva para esa parte y
    la reintenta sin contarlo como fallo. Así, un video de 500 MB por 4G puede tardar lo que haga
    falta: ninguna URL espera más de unos segundos antes de usarse.
  - Lo mismo con los archivos simples: el navegador autoriza cada archivo (`POST /api/subidas`)
    cuando le llega el turno en la cola, no al elegirlo. Si la URL vence antes del `PUT`, pide otra
    con `POST /api/subidas/{archivoId}/url`. La validación local de tipo y tamaño sí se muestra al
    elegir.
  - Las URLs de subida duran **5 minutos** o hasta el cierre, lo que ocurra primero. S3 valida la
    firma al empezar la petición, así que una transferencia lenta que empezó a tiempo termina.
  - Las URLs de lectura (galería, vista, descarga) duran 15 minutos.
- **Rationale**: lo exigen la constitución (Principio IV) y FR-009. Cinco minutos alcanza porque
  el navegador pide la URL justo antes de usarla. También acota a 5 minutos la ventana en que una
  URL emitida antes de un cierre anticipado sigue sirviendo.
- **Alternatives considered**:
  - POST prefirmado con `content-length-range`: impone el tamaño en S3, pero el AWS SDK for Java
    v2 no genera POST prefirmados; habría que implementarlo a mano. Descartado.
  - Transfer Acceleration: descartado por la constitución y por costo.
- **Control de tamaño y contenido**: al completar, el backend hace `HeadObject` y compara tamaño y
  `Content-Type` con lo autorizado, y lee los primeros 16 bytes (`GetObject` con `Range`) para
  verificar la firma del formato (JPEG `FF D8 FF`, PNG `89 50 4E 47`, WEBP `RIFF....WEBP`, HEIC,
  MP4 y MOV con caja `ftyp`). Si no coincide, borra el objeto y rechaza (edge case "contenido
  declarado inconsistente").
- **Verificado el 2026-11-14** (spike de R2, T028; pantalla `#spike` con `XMLHttpRequest`, URLs
  prefirmadas generadas en CloudShell con `boto3` contra el endpoint regional
  `s3.us-east-1.amazonaws.com`; el CORS de `boda-fotos-2026` permite `PUT` desde el dominio de
  Amplify):

  | Dispositivo y red | Prueba | Resultado |
  | --- | --- | --- |
  | iPhone, Vivaldi (motor WebKit), WiFi | `PUT` de 3,3 MB | HTTP 200 en 1,3 s; `ETag` legible desde JavaScript; tipo reportado `image/jpeg` |
  | iPhone, Vivaldi, 4G | `PUT` de 2,5 MB | HTTP 200 en 2,3 s; `ETag` legible |
  | iPhone, Vivaldi, 4G | URL firmada con `ContentLength=50000` y archivo de 2,5 MB | HTTP 403 `SignatureDoesNotMatch` |
  | iPhone, Safari | — | **Pendiente**: lista de chequeo, `quickstart.md` §4.1 |
  | Android, Chrome | — | **Pendiente**: lista de chequeo, `quickstart.md` §4.1 |

  Conclusiones:
  - El `PUT` directo con `XMLHttpRequest` funciona por 4G y el `ETag` es legible, así que el CORS
    (`ExposeHeaders: ETag`) cumple lo que el multipart necesita.
  - SC-003 se cumple con margen: 2,5 MB en 2,3 s por 4G. Extrapolando linealmente (estimación, no
    medición), una foto de 10 MB tardaría unos 9 s, por debajo de los 15 s.
  - **Decisión: el backend firma `Content-Length` con el tamaño declarado** (además del
    `Content-Type`). S3 rechaza con 403 cualquier cuerpo de otro tamaño desde el primer byte, así
    que no se puede subir más de lo autorizado con una URL válida. La verificación con
    `HeadObject` al completar se mantiene como segunda barrera. El navegador no necesita enviar
    nada especial: calcula `Content-Length` a partir del archivo.
  - **Confirmado con `S3Presigner` del AWS SDK for Java v2 (T040, 2026-11-14)**: el spike usó
    `boto3`; con el SDK de Java, el `PutObjectRequest` con `contentLength` y `contentType` produce
    `X-Amz-SignedHeaders=content-length;content-type;host`, y el `UploadPartRequest` con
    `contentLength`, `content-length;host`. Es decir, el tamaño queda firmado igual que en el spike,
    también por parte (10 MiB; la última, el resto). La prueba se hizo sin llamar a AWS, solo
    generando URLs con credenciales falsas.
  - **Endpoint regional**: para `us-east-1` el presigner del SDK usa por defecto el endpoint global
    (`bucket.s3.amazonaws.com`). `AwsConfig` fuerza el regional con `endpointOverride`
    (`https://s3.us-east-1.amazonaws.com`), que da `bucket.s3.us-east-1.amazonaws.com`, el mismo
    del spike.
  - **Consecuencia para el reintento**: un 403 en un `PUT` ahora puede ser URL vencida **o** tamaño
    distinto del firmado. El front reintenta con una URL nueva **una sola vez** por archivo o
    parte; si vuelve a dar 403, lo marca como fallido en vez de repetir en bucle.
- **Nota de permisos**: sin `s3:ListBucket`, `HeadObject` sobre una clave inexistente devuelve 403
  en vez de 404. El backend trata ambos como "no existe" y no se agrega `ListBucket`.

## R3. Claves de objetos y no sobrescritura

- **Decision**:
  - Original: `eventos/<eventoId>/originales/<archivoId>.<ext>`
  - Miniatura: `eventos/<eventoId>/miniaturas/<archivoId>.jpg`
  - `archivoId` es un UUID v4 generado por el backend; `<ext>` sale del formato validado, en
    minúsculas. El nombre original solo se guarda en DynamoDB.
- **Rationale**: claves generadas por el sistema (Principio III, FR-018). Con UUID v4 la
  probabilidad de colisión es despreciable (FR-019). El prefijo por evento permite que la política
  IAM se limite a `eventos/boda-2026/*` (Principio II).
- **Alternatives considered**: `If-None-Match: *` en el `PUT` (escritura condicional de S3).
  Garantiza la no sobrescritura en S3, pero obliga al navegador a enviar un encabezado firmado
  extra. No aporta frente a un UUID nuevo por archivo.

## R4. Miniaturas

- **Decision (aprobada el 2026-11-14 e incorporada a la spec: FR-026, FR-026a, Clarifications)**: la página de
  invitados genera en el celular una miniatura JPEG (lado mayor 480 px, calidad 0,7, unos 30–80 KB)
  para **videos y también para fotos JPG/PNG/WEBP**, con `<canvas>`. HEIC no se intenta: muestra
  ícono, como dice la spec.
  - Video: `<video muted playsinline>` con object URL, se busca el segundo 1 (o el 0 si dura
    menos), se dibuja el fotograma. Tiempo máximo 5 s; si falla o tarda, el video sube sin
    miniatura (FR-026a).
  - Foto: `createImageBitmap` + `<canvas>`, mismo tiempo máximo y misma regla de fallo.
  - La miniatura sube con su propia URL prefirmada (`image/jpeg`, máximo 1 MB) después del
    original. Como las URLs firman el tamaño (R2), el navegador genera la miniatura **antes** de
    autorizar y envía su tamaño en `tamanoMiniatura`. No cuenta para el tope de 300 archivos; sus bytes no se suman al tope de 5 GB.
- **Rationale**: la spec (FR-026 y la suposición "la única miniatura derivada es la del video")
  implica que la galería muestre las fotos con el **original** como miniatura. Una foto de celular
  pesa 3–8 MB; un bloque de 24 miniaturas serían 70–190 MB, incompatible con SC-007 ("cada bloque
  adicional en menos de 3 segundos en 4G o wifi"). Generar la miniatura en el celular es el mismo
  mecanismo que la spec ya exige para videos y sigue sin procesamiento en el servidor.
- **Alternatives considered**:
  - Mostrar el original con `loading="lazy"`: cumple la suposición literal, incumple SC-007. Queda
    como respaldo automático si una foto no tiene miniatura.
  - Generar miniaturas en el servidor (Lambda con eventos de S3): agrega servicios y va contra
    "sin procesamiento en el servidor". Descartado.

## R5. Datos en DynamoDB

- **Decision**: reutilizar `boda-fotos` para los archivos (con un GSI nuevo para la galería) y crear
  tres tablas pequeñas: `boda-evento`, `boda-admins`, `boda-sesiones`. Detalle en
  [data-model.md](data-model.md).
- **Rationale**: la tabla existente tiene como clave `photoId` y no se puede cambiar; sirve tal
  cual para los archivos. Tablas separadas permiten permisos IAM por tabla y por acción (mínimo
  privilegio) y son fáciles de crear y documentar en la consola. El GSI disperso (`galeria-index`)
  solo contiene archivos completados, así que la galería sale de un `Query` paginado, ordenado por
  fecha descendente, sin `Scan`.
- **Alternatives considered**: diseño de tabla única con `pk`/`sk`. Una tabla menos, pero obliga a
  recrear `boda-fotos` con otra clave y mezcla permisos. Descartado.
- **Contadores (FR-030b)**: viven en el ítem del evento y se actualizan con `ADD` dentro de la
  misma transacción (`TransactWriteItems`) que completa o elimina un archivo. Reflejan los archivos
  visibles en la galería (completados no eliminados). La hora de la última subida no retrocede al
  eliminar.

## R6. Autenticación de los novios

- **Decision**:
  - Contraseñas con **bcrypt** (`BCryptPasswordEncoder` de Spring Security, costo 12).
  - Sesión con un **token opaco** de 32 bytes aleatorios (Base64URL), enviado como
    `Authorization: Bearer <token>`. En DynamoDB se guarda solo el SHA-256 del token
    (`boda-sesiones`), con expiración por inactividad de 8 horas (FR-030) y TTL. Cerrar sesión
    borra el ítem.
  - El front de novios guarda el token en `sessionStorage` (se pierde al cerrar la pestaña).
  - Bloqueo (FR-025): 5 fallos consecutivos bloquean la cuenta 15 minutos (`intentosFallidos`,
    `bloqueadaHasta` en `boda-admins`). El mensaje es siempre el mismo, exista o no el usuario y
    esté o no bloqueado: "Usuario o contraseña incorrectos. Si fallaste varias veces, espera 15
    minutos e inténtalo de nuevo." Para usuarios inexistentes se compara contra un hash ficticio
    para igualar el tiempo de respuesta.
- **Rationale**:
  - bcrypt viene en Spring Security sin dependencias extra; Argon2id necesita Bouncy Castle
    (Principio I).
  - El token opaco guardado en DynamoDB sobrevive a reinicios y reemplazos de la instancia (con
    sesiones en memoria, cada reemplazo del Auto Scaling cerraría la sesión de los novios),
    permite cerrar sesión de verdad y no necesita ningún secreto de firma.
  - Con un encabezado en vez de cookie no hay CSRF y funciona igual con el plan B de R1.
- **Alternatives considered**:
  - JWT firmado: necesitaría un secreto en SSM y no permite invalidar en el logout sin una lista
    negra. Descartado.
  - Cookie `HttpOnly`: más resistente a XSS, pero depende de cómo el proxy de Amplify reenvíe
    `Set-Cookie` y exige protección CSRF. El riesgo de XSS es bajo: Angular escapa la
    interpolación y no se renderiza HTML de terceros. Descartado por plazo.
  - Argon2id: descartado por la dependencia extra; la constitución acepta bcrypt.

## R7. Cuentas de administrador (FR-031)

- **Decision**: el mismo `.jar` tiene un modo de línea de comandos que no levanta el servidor web:
  `java -jar app.jar hash-contrasena`. Pide la contraseña sin eco (`System.console()`) y muestra el
  hash bcrypt. El responsable técnico crea o restablece la cuenta desde **CloudShell** con
  `aws dynamodb put-item` / `update-item`, que usa su sesión de consola con MFA.
- **Rationale**: no hay endpoint web para crear cuentas, el rol de la EC2 no necesita `PutItem` en
  `boda-admins` y no hace falta crear access keys. Restablecer = mismo procedimiento + poner
  `intentosFallidos` en 0.
- **Alternatives considered**: endpoint de "bootstrap" protegido por un secreto (superficie de
  ataque innecesaria); ejecutar el comando en la instancia por Session Manager (exige ampliar el
  rol). Descartados.

## R8. Sesión de subida de invitados (FR-004a, FR-012a)

- **Decision**: `POST /api/sesiones-subida` crea un ítem en `boda-sesiones` con un identificador
  aleatorio de 128 bits emitido por el servidor. El navegador lo guarda en `localStorage` junto
  con el último nombre y lo envía en el encabezado `X-Sesion-Subida`. El tope se aplica con un
  `UpdateItem` condicional atómico:
  `ADD archivos :1, bytes :tamano` con condición `archivos < :maxArchivos AND bytes <= :maxBytesMenosTamano`.
  Reintentar un archivo ya autorizado pide una URL nueva para el mismo `archivoId` y no suma al
  tope. El TTL de la sesión es el cierre de la recepción + 1 día.
- **Rationale**: el tope es exacto aunque haya peticiones simultáneas, sin bloqueos ni lecturas
  previas.
- **Alternatives considered**: firmar el identificador (HMAC) y no guardarlo; no permite llevar los
  contadores. Descartado.

## R9. Ventana de recepción y configuración (FR-013 a FR-017, FR-029, FR-032)

- **Decision**: el ítem del evento en `boda-evento` guarda en **hora local de Cali**
  (`2026-11-19T00:00`) la apertura, el cierre y el fin de disponibilidad, más los topes por sesión
  y el cierre anticipado. El backend lo lee con lectura fuertemente consistente en cada petición
  que lo necesita (sin caché) y convierte con `ZoneId.of("America/Bogota")`.
  - Estado de la recepción: `NO_INICIADA` si ahora < apertura; `CERRADA` si ahora ≥ cierre o hay
    cierre anticipado; `ABIERTA` en otro caso.
  - **Finalización** (`completar`), en ambos modos: se rechaza si la recepción está cerrada. Si se
    rechaza, el backend borra el objeto (o aborta el multipart). Es más estricto que lo mínimo de
    FR-016, pero da una sola regla y cumple el escenario 2 de la US4 ("ni finalizaciones" tras el
    cierre anticipado).
  - Fin de disponibilidad: `2026-12-01T23:59:59` local. Después, los endpoints de galería y URLs
    responden `410 PERIODO_TERMINADO`.
  - Las marcas de tiempo de los archivos se guardan en UTC (ISO-8601 con `Z`).
- **Rationale**: editar el ítem en la consola de DynamoDB cambia la configuración al instante, sin
  redesplegar. Es lo que hace posible probar en producción antes del 19 de noviembre (abrir la
  ventana temporalmente y restaurarla; ver quickstart) y cumple FR-017 y el Principio III.
- **Alternatives considered**: variables de entorno (cambiar una fecha obliga a una versión nueva
  de la launch template y a reemplazar la instancia); SSM Parameter Store (lo mismo, o caché con
  retraso). Descartados.

## R10. Despliegue del backend

- **Decision**: bucket privado de artefactos `boda-artefactos-248033171214` con versionado. La
  launch template (versión nueva) descarga `backend/app.jar` al arrancar y lo ejecuta como servicio
  `systemd` con el usuario `boda`. Auto Scaling group `boda-backend-asg` con mínimo, deseado y
  máximo 1, en `us-east-1a` y `us-east-1b`, registrado en `boda-backend-tg`, health check ELB, 600
  s de gracia (el arranque de una instancia nueva tarda unos 4 minutos: `dnf update`, Java y el
  `.jar`; con 300 s el ASG la daba por muerta antes de que terminara). Desplegar = `mvn package` → `aws s3 cp` → *instance refresh* con
  `MinHealthyPercentage=100` y `MaxHealthyPercentage=200` (levanta la nueva antes de apagar la
  vieja, sin corte). Volver atrás = restaurar la versión anterior del objeto en S3 y repetir el
  instance refresh.
- **Orden de creación**: bucket de artefactos → política IAM → `.jar` mínimo con
  `/actuator/health` subido → launch template v3 → ASG. La user data usa `set -euo pipefail`, así
  que si `backend/app.jar` no existe el `aws s3 cp` falla, `boda.service` no se crea, el health
  check nunca pasa y el ASG reemplaza la instancia una y otra vez. Por eso el ASG se crea solo
  después de subir el `.jar`.
- **Rationale**: es la opción con menos piezas que funciona con Auto Scaling: nada que instalar
  aparte de Corretto, sin agentes ni registros de imágenes. El versionado del bucket da el
  rollback gratis.
- **Alternatives considered**:
  - CodeDeploy o un pipeline de CI/CD: más servicios y configuración. Con un solo desarrollador y
    pocos despliegues, un procedimiento de 3 comandos documentado alcanza (Principio I).
    Descartado.
  - Docker + ECR: agrega runtime y registro. Descartado.
  - AMI propia: más lenta de iterar. Descartado.
- **User data propuesta** (versión 3 de `boda-backend-lt`; reemplaza la versión 2):

```bash
#!/bin/bash
set -euo pipefail
dnf update -y
dnf install -y java-25-amazon-corretto-headless
id boda >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin boda
mkdir -p /opt/boda
aws s3 cp s3://boda-artefactos-248033171214/backend/app.jar /opt/boda/app.jar --region us-east-1
chown -R boda:boda /opt/boda
cat > /etc/systemd/system/boda.service <<'EOF'
[Unit]
Description=Backend boda (Spring Boot)
After=network-online.target
Wants=network-online.target

[Service]
User=boda
WorkingDirectory=/opt/boda
Environment=AWS_REGION=us-east-1
Environment=S3_BUCKET=boda-fotos-2026
Environment=EVENT_ID=boda-2026
Environment=DYNAMODB_TABLE_PHOTOS=boda-fotos
Environment=DYNAMODB_TABLE_EVENT=boda-evento
Environment=DYNAMODB_TABLE_ADMINS=boda-admins
Environment=DYNAMODB_TABLE_SESSIONS=boda-sesiones
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=60 -jar /opt/boda/app.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now boda
```

## R11. Secretos y SSM Parameter Store

- **Decision**: el diseño **no necesita secretos**. Las credenciales de AWS salen del rol de la
  instancia, los tokens son aleatorios y se guardan con hash, y las contraseñas viven en DynamoDB
  como hash. No se crean parámetros de SSM ni se da `ssm:GetParameter` al rol.
- **Rationale**: mínimo privilegio y una pieza menos. La constitución exige que lo sensible esté
  en SSM *si existe*; aquí no existe.
- **Convención si aparece un secreto**: `SecureString` en `/boda/<nombre>`, permiso
  `ssm:GetParameter` solo sobre `arn:aws:ssm:us-east-1:248033171214:parameter/boda/*`, leído al
  arrancar con el SDK (sin Spring Cloud AWS).

## R12. Front-ends en Amplify (monorepo)

- **Decision**: dos proyectos Angular 21 independientes, `front-invitados/` y `front-novios/`, cada
  uno con su `package.json`. Un `amplify.yml` en la raíz con `applications:` y `appRoot` por app;
  cada app de Amplify tiene la variable `AMPLIFY_MONOREPO_APP_ROOT`. Las dos apps se conectan a
  la rama **`david/release`**, no a `main`: se trabaja en ramas `david/...` y se hace merge a
  `david/release` solo cuando algo está listo; del 19 al 22 solo entran correcciones críticas. El
  subdominio de Amplify sale del nombre de la rama (`/` pasa a `-`), por lo que la rama no se
  renombra después de imprimir el QR (plan, K6). En `preBuild` se fija Node 22 (`nvm use 22`), que cumple el mínimo de Angular 21. Se
  publica `dist/<app>/browser`. Sin librerías de UI: CSS propio, móvil primero.
- **Rewrites, en este orden**:
  1. `/api/<*>` → `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` (200).
  2. `</^[^.]+$|\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json|webp)$)([^.]+$)/>` →
     `/index.html` (200).
- **Rationale**: dos apps separadas cumplen "no comparten despliegue ni rutas públicas". Duplicar
  un cliente HTTP pequeño cuesta menos que mantener un workspace compartido. Una rama de
  publicación dedicada evita que un push a medio hacer llegue a producción, sin pipeline extra.
- **Alternatives considered**: un workspace Angular con dos proyectos y una librería común; más
  configuración para muy poco código compartido. Descartado.
- **Desarrollo local**: `ng serve` con `proxy.conf.json` (`/api` → `http://localhost:8080`). El
  CORS del bucket ya permite `http://localhost:4200`, y se quita antes del 19 de noviembre.

## R13. Compatibilidad móvil

- **Decision**:
  - `<input type="file" multiple accept="image/*,video/mp4,video/quicktime">`. **El `accept` no
    debe incluir `image/heic` ni `.heic`**: cuando el selector pide un tipo concreto de imagen,
    iOS entrega las fotos HEIC tal cual; con `image/*` las convierte a JPEG al pasarlas al
    navegador, y así hay miniatura (la hace el celular con `<canvas>`, que no decodifica HEIC) y la
    foto se ve en cualquier navegador. Con `image/*` el selector también ofrece formatos que el
    sistema no admite (por ejemplo GIF): los rechaza la validación local de `tipos.ts` y, si se
    saltara, el servidor (`TIPO_NO_PERMITIDO`). El servidor sigue aceptando `.heic` (FR-010) para
    los casos en que el archivo llegue sin convertir.
  - El tipo se deduce de la **extensión** cuando el navegador entrega `file.type` vacío (pasa con
    HEIC y MOV en Android). El `Content-Type` firmado sale de la tabla del backend, no del
    navegador.
  - Progreso con `XMLHttpRequest` directo (`xhr.upload.onprogress`), como en el spike de R2
    (T027, verificado en iPhone por WiFi y 4G); `fetch` no informa el progreso de subida. Los
    `PUT` a S3 no pasan por `HttpClient`; el resto de las llamadas al API sí.
  - Hasta 3 archivos en paralelo; los fallidos quedan en la lista con botón "Reintentar
    fallidos".
  - Mensaje visible mientras sube: "Mantén esta pantalla abierta hasta que terminen las
    subidas." En iOS, Safari suspende la página en segundo plano y la subida falla; el reintento
    lo resuelve.
  - En la vista completa del front de novios, HEIC y algunos MOV (HEVC) no se ven en Chrome de
    escritorio: se muestra el ícono y el botón de descarga.
  - **Observado en el spike (2026-11-14)**: un iPhone (Vivaldi, motor WebKit) entrega las fotos
    HEIC al navegador **ya convertidas a JPEG**: `file.type = image/jpeg` y **también cambia la
    extensión** (`IMG_6816.jpeg` por WiFi y `360919F4-7D59-4FF9-86D2-E09FB1C7F30A.jpeg` por 4G).
    Por eso la regla del servidor (el formato lo decide la extensión y se rechaza si el tipo
    declarado la contradice) **no cambia**: no hay `.heic` con contenido JPEG. Esto se observó con
    el spike, cuyo `<input type="file">` no tenía `accept`; la conversión depende del `accept`
    (ver arriba).
  - **Por verificar en Safari con una foto HEIC real** (lista de chequeo, `quickstart.md` §4.1),
    con el `accept` de arriba: que el archivo llegue como `.jpeg` con `image/jpeg`, que el front
    genere la miniatura y que la galería la muestre. Si Safari entregara el HEIC sin convertir,
    esa foto se sube igual (FR-010) pero sin miniatura y con ícono en la galería.
  - Vivaldi en iOS usa el motor de WebKit como todos los navegadores de iOS, pero no es Safari: la
    verificación en Safari y en Android/Chrome queda para la lista de chequeo (§4.1).
- **Rationale**: son las fallas típicas de subidas desde el navegador del celular. Se descubren
  solo con dispositivos reales, por eso van en la verificación del día 1–2 (quickstart).

## R14. Backend: dependencias y operación

- **Decision**:
  - Starters: `spring-boot-starter-web`, `spring-boot-starter-security`,
    `spring-boot-starter-validation`, `spring-boot-starter-actuator`.
  - AWS SDK v2 (BOM): `s3`, `dynamodb`. Sin Spring Cloud AWS.
  - Actuator expone solo `health`, sin detalles ni chequeos de dependencias (si DynamoDB fallara un
    momento, el ALB no debe dar por muerta a la única instancia).
  - Logs a `journald` (`journalctl -u boda` por Session Manager).
  - Pruebas automáticas: solo unitarias JUnit 5 de la lógica pura (estado de la ventana con zona
    horaria, validación de tipo, tamaño y firma de formato, cálculo de expiración de URLs). Son
    opcionales según la constitución, pero baratas y protegen el Principio III.
- **Alternatives considered**: agente de CloudWatch Logs para conservar logs si la instancia se
  reemplaza; útil pero es otra pieza. Queda fuera; se puede agregar con justificación si hace
  falta durante las pruebas.

## R15. Monitoreo (FR-030a, SC-010)

- **Decision**: usar las alarmas ya creadas (`boda-backend-sin-salud`, `boda-api-errores-5xx`) y el
  tema SNS `boda-alertas`; activar sus acciones cuando el backend esté sano.
- **Limitación conocida**: el ALB no separa métricas por ruta, así que la alarma de 5xx mide todo
  el API y no solo la autorización de subidas, como pide FR-030a. Se acepta: durante la recepción,
  casi todo el tráfico del API es de subidas.

## R16. Estimación de costos (Principio V)

Precios de lista de `us-east-1` usados para el cálculo. **Verificarlos en AWS Pricing Calculator
antes de desplegar**; se tomaron de la tabla pública conocida, no de una consulta de hoy.

**Supuestos de volumen** (spec: 2.000–5.000 archivos): escenario alto de 4.000 fotos de 4 MB
(16 GB) + 1.000 videos de 60 MB (60 GB) ≈ **80 GB**, unos 200 videos ≥ 100 MB en multipart. Cómputo
encendido del 2026-11-14 al 2026-12-02: **18 días ≈ 432 h**. Los novios descargan todo una o dos
veces.

| Concepto | Precio de lista | Cálculo | Costo |
| --- | --- | --- | --- |
| EC2 `t3.micro` | USD 0,0104/h | 432 h | 4,49 |
| EBS gp3 8 GB | USD 0,08/GB-mes | 8 GB × 0,6 mes | 0,38 |
| ALB (hora) | USD 0,0225/h | 432 h | 9,72 |
| ALB (LCU) | USD 0,008/LCU-h | ~0,3 LCU promedio × 432 h | ~1,04 |
| IPv4 públicas (ALB en 2 AZ + instancia) | USD 0,005/h por IP | 3 × 432 h | 6,48 |
| CloudFront `boda-cf` | Plan Free | — | 0,00 |
| S3 almacenamiento (Standard) | USD 0,023/GB-mes | 80 GB × ~0,5 mes en noviembre | ~0,92 |
| S3 PUT/POST/LIST | USD 0,005 por 1.000 | ~15.000 (originales, miniaturas, partes) | 0,08 |
| S3 GET/HEAD | USD 0,0004 por 1.000 | ~100.000 | 0,04 |
| Transferencia S3 → internet | 100 GB/mes gratis, luego USD 0,09/GB | 80–160 GB de descargas | 0,00–5,40 |
| Amplify build | USD 0,01/min | ~2 apps × 3 min × 40 builds | ~2,40 |
| Amplify datos servidos y alojamiento | USD 0,15/GB; USD 0,023/GB-mes | ~2 GB | ~0,30 |
| DynamoDB on-demand | USD 0,625 / M escrituras; 0,125 / M lecturas | ~50.000 escrituras, ~200.000 lecturas | ~0,06 |
| DynamoDB PITR y almacenamiento | USD 0,20 / 0,25 por GB-mes | < 0,1 GB | ~0,05 |
| CloudWatch alarmas | USD 0,10 por métrica de alarma | 4 métricas (1 + 3 de la expresión) | 0,40 |
| SNS correo, Session Manager, S3 de artefactos | — | volúmenes mínimos | ~0,00 |
| **Total del periodo del evento** | | | **≈ 26–32 USD** |

- Lo que más pesa es el **ALB con sus IPv4 públicas (~17 USD)**, más que S3 o EC2.
- El presupuesto `boda-presupuesto` es de USD 30/**mes**. El gasto se reparte entre noviembre
  (~22–24 USD) y diciembre (~3–8 USD, según cuánto descarguen los novios), así que no debería
  superarlo. El aviso del 85 % puede saltar a fin de noviembre si las descargas empiezan antes.
- **Costo que queda después del 2026-12-02** (apagado el cómputo): S3 ≈ 80 GB × 0,023 =
  **~1,84 USD/mes** mientras se conserve el material (FR-033, SC-009: costo esperado), más
  centavos de DynamoDB y Amplify.
- Si la cuenta tiene menos de 12 meses, la capa gratuita cubre parte de EC2, EBS y Amplify; no
  se descuenta en la tabla.
