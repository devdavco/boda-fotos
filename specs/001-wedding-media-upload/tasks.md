---
description: "Lista de tareas del feature 001-wedding-media-upload"
---

# Tasks: Fotos y videos de la boda vía QR

**Input**: documentos de diseño en `specs/001-wedding-media-upload/` (plan.md, spec.md, research.md, data-model.md, contracts/api.md, contracts/ui.md, quickstart.md)

**Prerrequisitos**: plan.md y spec.md (presentes). Constitución v2.0.0.

**Generación**: hecha a mano siguiendo `.claude/skills/speckit-tasks/SKILL.md` (no hay PowerShell para `setup-tasks.ps1`): la carpeta del feature es `specs/001-wedding-media-upload`, la plantilla es `.specify/templates/tasks-template.md` y no hay `.specify/extensions.yml`, así que no hay hooks.

**Pruebas**: la spec y la constitución no piden pruebas automáticas (la verificación principal es manual, `quickstart.md` §4). Se incluyen solo tres tareas de pruebas unitarias JUnit 5 de lógica pura, marcadas *(Opcional)*; son lo primero que se recorta según el plan.

**Organización**: Fase 1 (preparación y backend mínimo, hoy), Fase 2 (fundamentos), una fase por historia de usuario en orden de prioridad (US1 y US2 son P1, US3 es P2, US4 y US5 son P3) y una fase final de verificación. El orden de las tareas sigue el calendario del plan.

## Formato: `[ID] [P?] [Historia] **[Quién]** Descripción`

- **[P]**: se puede hacer en paralelo (archivos distintos y sin depender de tareas incompletas).
- **[USn]**: historia de usuario de `spec.md` (solo en las fases de historias).
- **Quién hace cada tarea**:
  - **[CÓDIGO]**: se escribe o edita en el repositorio (incluye la documentación de `infra/README.md` y operaciones de `git`).
  - **[CONSOLA]**: la haces tú en la consola de AWS, CloudShell o la AWS CLI. Cada una indica dónde documentarla en `infra/README.md`.
  - **[MANUAL]**: verificación con celulares, `curl` o la consola; no produce código.
- Cada tarea cita su archivo o documento de referencia. Las referencias entre tareas usan el ID (`T0xx`).

## Rutas del proyecto (de `plan.md`)

- Backend: `backend/src/main/java/co/boda/fotos/{config,recepcion,subida,galeria,admin,error}/`, `backend/src/main/resources/application.yml`, `backend/src/test/java/co/boda/fotos/`
- Front de invitados: `front-invitados/src/app/{estado,subida,api}/`
- Front de novios: `front-novios/src/app/{auth,galeria,archivo}/`
- Monorepo: `amplify.yml` e `infra/README.md` en la raíz

## Fase 1: Preparación y backend mínimo en producción (Sáb 14 de noviembre)

**Propósito**: poner hoy el `.jar` mínimo detrás del ALB (orden del plan: bucket de artefactos → `.jar` mínimo subido → launch template v3 → ASG), crear la rama `david/release` con el esqueleto del front de invitados, conectar Amplify y hacer los *spikes* de R1 y R2.

**Ya creado en AWS (no hay tareas de creación)**: tablas `boda-evento` (con el ítem `boda-2026`), `boda-admins` y `boda-sesiones` (con TTL), índice `galeria-index` en `boda-fotos`, bucket `boda-artefactos-248033171214` y la política `boda-backend-policy` v3 (el JSON del plan, ya aplicado). Solo hay tareas para documentarlos en `infra/README.md`.

### 1A. Esqueleto del backend → `.jar` → launch template v3 → ASG

- [x] T001 **[CÓDIGO]** Crear `backend/pom.xml`: parent `spring-boot-starter-parent` **4.1.2** (la última 4.1.x en Maven Central al 2026-11-14), `java.version` 25, dependencias `spring-boot-starter-webmvc` (en Boot 4 el starter web se llama así), `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-actuator` y `spring-boot-starter-test` (scope test); BOM `software.amazon.awssdk:bom` con `s3` y `dynamodb`; `<finalName>app</finalName>` y `spring-boot-maven-plugin` para que `mvn package` genere `backend/target/app.jar` ejecutable con `java -jar`. Si el build falla por el nombre de algún starter, revisar los de Boot 4 en start.spring.io
- [x] T002 [P] **[CÓDIGO]** Crear `.gitignore` en la raíz con `backend/target/`, `node_modules/`, `dist/`, `.angular/`, `.DS_Store` y `*.log` (evita versionar el `.jar` y las dependencias)
- [x] T003 [P] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/BodaApplication.java` con `@SpringBootApplication` y un `main` que arranca el servidor web (el modo línea de comandos `hash-contrasena` se agrega en T071)
- [x] T004 [P] **[CÓDIGO]** Crear `backend/src/main/resources/application.yml`: `server.port: 8080`; `management.endpoints.web.exposure.include: health`; `management.endpoint.health.show-details: never` (health solo de liveness, sin chequeos de DynamoDB ni S3, research R14)
- [x] T005 **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/config/SecurityConfig.java` (esqueleto): `SecurityFilterChain` sin estado, CSRF desactivado, `permitAll` para `/actuator/health` y para **cualquier método** en `/api/recepcion/estado`, y `denyAll` para el resto. **Sin esto el starter de seguridad responde 401 a `/actuator/health`, el ALB nunca ve la instancia sana y el ASG la reemplaza en bucle**. Se amplía en T036
- [x] T006 **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/recepcion/RecepcionController.java` con `GET /api/recepcion/estado` de **respuesta fija**: `{"estado":"NO_INICIADA","aperturaEn":"2026-11-19T05:00:00Z","cierreEn":"2026-11-22T17:00:00Z","ahora":<Instant.now()>}` (forma de `contracts/api.md`; hoy es verdad que no ha iniciado). Se reemplaza por el cálculo real en T059
- [x] T007 **[CÓDIGO]** Compilar y probar en local (Java 25 y Maven 3.9+, `quickstart.md` §1): `cd backend && mvn -q package && java -jar target/app.jar`; `curl -s localhost:8080/actuator/health` → `{"status":"UP"}`, `curl -s localhost:8080/api/recepcion/estado` → el JSON fijo y `curl -s -X POST localhost:8080/api/recepcion/estado -o /dev/null -w '%{http_code}'` → `405` (llega a Spring, no a seguridad). Archivo: `backend/target/app.jar`
- [x] T008 **[CONSOLA]** Subir el `.jar` mínimo: `aws s3 cp backend/target/app.jar s3://boda-artefactos-248033171214/backend/app.jar --region us-east-1` y confirmar con `aws s3 ls s3://boda-artefactos-248033171214/backend/`. **Debe existir antes de crear la launch template v3 y el ASG** (plan, paso 7). Se documenta en T013 (`infra/README.md`)
- [x] T009 **[CONSOLA]** Crear la versión 3 de la launch template `boda-backend-lt` a partir de la v2 (AL2023 x86_64, `t3.micro`, sin key pair, `boda-app-sg`, instance profile `boda-ec2-role`, tags `proyecto=boda` en instancias y volúmenes) con la **user data de `research.md` R10** (instala `java-25-amazon-corretto-headless`, descarga `backend/app.jar` y arranca `boda.service`) y marcarla como versión por defecto. Requiere T008. Se documenta en T015
- [x] T010 **[CONSOLA]** Crear el Auto Scaling group `boda-backend-asg` **solo con el `.jar` ya en el bucket y la v3 por defecto**: launch template `boda-backend-lt` (Default), subredes `us-east-1a` y `us-east-1b` de la VPC por defecto, asociado al target group `boda-backend-tg`, health check ELB con gracia de **600 s** (el arranque tarda unos 4 minutos), mínimo/deseado/máximo = 1, instance maintenance policy *launch before terminating* (mínimo 100 %, máximo 200 %), sin políticas de escalado, tags `proyecto=boda` propagados. Se documenta en T015
- [x] T011 **[MANUAL]** Comprobar el backend mínimo en producción (hasta 10 min tras crear el ASG): `boda-backend-tg` con 1 instancia *healthy* y `curl -s https://ddl2si3h6lu7z.cloudfront.net/api/recepcion/estado` devuelve el JSON fijo. Si no queda sana: Session Manager → `sudo journalctl -u boda -n 100` y `sudo tail -n 100 /var/log/cloud-init-output.log` (causas probables: `.jar` ausente, Java 25 sin instalar, 401 en el health). Criterio de salida: `quickstart.md` §3

### 1B. Documentar en `infra/README.md` lo ya creado (una sola persona edita el archivo: sin `[P]`)

- [x] T012 **[CÓDIGO]** Documentar en `infra/README.md` §3 las tablas **ya creadas** `boda-evento` (PK `eventoId`), `boda-admins` (PK `usuario`) y `boda-sesiones` (PK `id`, TTL sobre `expiraEnEpoch`), el GSI `galeria-index` de `boda-fotos` (PK `galeriaEvento`, SK `galeriaOrden`, proyección *All*) y el ítem `boda-2026` de `boda-evento` (comando `put-item` de `plan.md`, sección "Recursos AWS"); actualizar la tabla "Estado actual". Pedirte que confirmes capacidad on-demand, deletion protection y PITR tal como las creaste
- [x] T013 **[CÓDIGO]** Documentar en `infra/README.md` una sección nueva para el bucket `boda-artefactos-248033171214` (privado, ACLs disabled, SSE-S3, versionado activado) y el procedimiento de despliegue del backend: `mvn -q package` → `aws s3 cp` → `aws autoscaling start-instance-refresh --auto-scaling-group-name boda-backend-asg --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200}'`; rollback restaurando la versión anterior del objeto y repitiendo el refresh (research R10, `quickstart.md` §3)
- [x] T014 **[CÓDIGO]** Reemplazar en `infra/README.md` §4 el JSON de `boda-backend-policy` por la **v3 aplicada** (copiar exacto de `plan.md`, bloque "Política `boda-backend-policy`") y anotar la tabla de acciones por operación; quitar "se ampliará según el plan"
- [x] T015 **[CÓDIGO]** Actualizar `infra/README.md`: §6 con la v3 de `boda-backend-lt` (user data de `research.md` R10 y quitar el "Pendiente: agregar al user data"), sección nueva del ASG `boda-backend-asg` con la configuración de T010, la tabla "Estado actual" y "Pendientes". Depende de T009 y T010

### 1C. Rama `david/release`, esqueleto del front de invitados y Amplify

- [x] T016 **[CÓDIGO]** Crear el proyecto Angular 21 `front-invitados/` con Node 22 (`npx @angular/cli@21 new front-invitados --routing=false --style=css --ssr=false --skip-git`) en la raíz del repo; comprobar que `npm run build` genera `front-invitados/dist/front-invitados/browser`. Archivo base: `front-invitados/package.json`
- [x] T017 [P] **[CÓDIGO]** Crear `front-invitados/proxy.conf.json` (`/api` → `http://localhost:8080`, `secure: false`) y referenciarlo en `front-invitados/angular.json` (`serve.options.proxyConfig`) para `ng serve` (research R12)
- [x] T018 [P] **[CÓDIGO]** Reemplazar `front-invitados/src/app/app.ts` (y su plantilla) por una pantalla mínima que llama a `GET /api/recepcion/estado` con `HttpClient` (provider en `front-invitados/src/app/app.config.ts`) y muestra "Estado de la recepción: <estado>"; sirve para el *spike* de R1
- [x] T019 [P] **[CÓDIGO]** Crear `amplify.yml` en la raíz del repo con `applications:` para `front-invitados` y `front-novios` (`appRoot` por app): `preBuild` con `nvm use 22` y `npm ci`, `build` con `npm run build`, `artifacts.baseDirectory: dist/<app>/browser`, `files: '**/*'` y cache de `node_modules` (research R12)
- [x] T020 **[CÓDIGO]** Crear la rama **`david/release`** (hoy no existe en `origin`): `git branch david/release main && git push -u origin david/release`; trabajar el esqueleto (T016–T019) en una rama `david/esqueleto-front` y mergearla a `david/release` (`git merge --no-ff`), push. Debe quedar `amplify.yml` y `front-invitados/` en `david/release` **antes** de conectar Amplify. No renombrar nunca esta rama (riesgo K6). Archivo: `amplify.yml`
- [x] T021 **[CONSOLA]** Crear la app de Amplify `boda-invitados`: repo `devdavco/boda-fotos`, rama **`david/release`**, monorepo con `AMPLIFY_MONOREPO_APP_ROOT=front-invitados`, tag `proyecto=boda`. Rewrites en este orden (research R12): (1) `/api/<*>` → `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` tipo 200; (2) el rewrite de SPA `</^[^.]+$|\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json|webp)$)([^.]+$)/>` → `/index.html` tipo 200. **Dominio** (la URL del QR): `https://david-release.d2x2ckep95pz3x.amplifyapp.com`. Requiere T020. Se documenta en T025
- [ ] T022 **[CONSOLA]** Crear la app de Amplify `boda-novios`: igual que T021 pero `AMPLIFY_MONOREPO_APP_ROOT=front-novios`. Su primer build fallará hasta que exista `front-novios/` en `david/release` (esperado, plan recurso 12; se resuelve en T083). Anotar su dominio. **Pospuesta hasta que exista `front-novios/`.** Al crearla, comprobar también si un push que toca solo `front-invitados/` reconstruye `boda-novios` (suposición de K7; viene de T023): si sí, mantener la condición de desactivar el *auto build* de `boda-invitados` del 19 al 22; si no, esa condición sobra y se quita del plan
- [x] T023 **[MANUAL]** *Spike* de R1 (proxy de Amplify) con el backend mínimo: abrir el dominio de `boda-invitados` en el iPhone y ver el estado; `curl -s https://<dominio-amplify>/api/recepcion/estado` igual al de CloudFront; `curl -i -X POST` y `-X DELETE` al mismo path deben devolver **405 desde Spring** (prueba que ambos métodos llegan al backend). El reenvío del cuerpo JSON y de los encabezados `Authorization` y `X-Sesion-Subida` solo se puede probar con endpoints reales: queda en T052. La comprobación de si un push que toca solo `front-invitados/` reconstruye `boda-novios` (suposición de K7) pasó a T022. Registrar el resultado en T029
- [x] T024 **[CONSOLA]** Actualizar el CORS de `boda-fotos-2026`: `AllowedOrigins` = dominio de `boda-invitados` y `http://localhost:4200`; `AllowedMethods` = `["PUT"]`; `AllowedHeaders` = `["*"]`; `ExposeHeaders` = `["ETag"]`; `MaxAgeSeconds` 3000; quitar el dominio de CloudFront (plan, recurso 13). El dominio de `boda-novios` no se agrega. Se documenta en T025
- [x] T025 **[CÓDIGO]** Documentar en `infra/README.md`: §11 con las dos apps de Amplify (rama `david/release`, `AMPLIFY_MONOREPO_APP_ROOT`, rewrites, dominios reales), §2 con el CORS nuevo, y las tablas "Estado actual" y "Pendientes". Depende de T021, T022 y T024

### 1D. *Spike* de R2: subida directa a S3 desde celulares reales

- [x] T026 **[CONSOLA]** En CloudShell generar una URL prefirmada `PUT` de 5 min para `eventos/boda-prueba/spike-1.jpg` con `Content-Type: image/jpeg` (por ejemplo `python3 -c` con `boto3.client('s3',region_name='us-east-1').generate_presigned_url('put_object',Params={'Bucket':'boda-fotos-2026','Key':'eventos/boda-prueba/spike-1.jpg','ContentType':'image/jpeg'},ExpiresIn=300)`). Genera una segunda con otra clave para la prueba de tamaño (T028)
- [x] T027 [P] **[CÓDIGO]** Agregar a `front-invitados/src/app/spike.ts` una pantalla **temporal** (se muestra solo con `#spike` en la URL; se elimina en T094): campo para pegar la URL prefirmada, selector de archivo y `PUT` con `XMLHttpRequest` y el `Content-Type` con el que se firmó (por defecto `image/jpeg`), mostrando progreso, el código HTTP y el encabezado `ETag` de la respuesta. Se incluye desde `front-invitados/src/app/app.ts`
- [x] T028 **[MANUAL]** Probar el *spike* de R2 en un iPhone/Safari y un Android/Chrome con datos móviles: el `PUT` termina y el objeto aparece en S3; el `ETag` se lee en JavaScript (CORS); subir un archivo **más grande** que el declarado al generar la URL y anotar si S3 lo rechaza (K9, research R2: si `S3Presigner` firma `Content-Length`). Requiere T026, T027 y T024
- [x] T029 **[CÓDIGO]** Registrar en `specs/001-wedding-media-upload/research.md` (R1 y R2, líneas "Por verificar el día 1") el resultado de T023 y T028; si el rewrite falla, activar el plan B de R1 (URL directa a CloudFront y CORS en Spring) y avisar

---

## Fase 2: Fundamentos del backend (Dom 15, mañana)

**Propósito**: configuración, clientes de AWS, catálogo de errores y lectura de la ventana de recepción que usan todas las historias.

**⚠ CRÍTICO**: ninguna historia puede empezar hasta terminar esta fase.

- [x] T030 [P] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/config/BodaProperties.java` (`@ConfigurationProperties("boda")`) con las variables de entorno `AWS_REGION`, `S3_BUCKET`, `EVENT_ID`, `DYNAMODB_TABLE_PHOTOS`, `DYNAMODB_TABLE_EVENT`, `DYNAMODB_TABLE_ADMINS`, `DYNAMODB_TABLE_SESSIONS` (mapeadas en `application.yml`; son las del `boda.service` de research R10) y las constantes del diseño: URL de subida **5 minutos**, URL de lectura **15 minutos**, foto máx. **41.943.040 B (40 MB)**, video máx. **524.288.000 B (500 MB)**, umbral multipart **104.857.600 B (100 MB)**, parte de **10 MiB**, miniatura máx. **1 MB**
- [x] T031 [P] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/config/AwsConfig.java` con los beans `S3Client`, `S3Presigner` y `DynamoDbClient` (región de `BodaProperties`, cadena de credenciales por defecto del SDK: rol de la EC2 en AWS, perfil de AWS CLI en local; nunca access keys en el repo)
- [x] T032 [P] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/error/CodigoError.java` y `ApiException.java` con **todos** los códigos de `contracts/api.md` (sección "Errores"): HTTP, `codigo` y `mensaje` en español **verbatim** (por ejemplo `ARCHIVO_MUY_GRANDE` → 400 "La foto supera el máximo de 40 MB." o "El video supera el máximo de 500 MB. Elige uno más corto."; `PERIODO_TERMINADO` → 410)
- [x] T033 **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/error/ManejadorErrores.java` (`@RestControllerAdvice`): `ApiException` → `{"codigo","mensaje"}`; JSON mal formado o validación → `PETICION_INVALIDA`; cualquier otra excepción → `ERROR_INTERNO` 500 sin detalles técnicos (se registra en el log). Depende de T032
- [x] T034 [P] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/recepcion/Evento.java` y `EventoRepository.java`: `GetItem` con `ConsistentRead=true` sobre `boda-evento` (clave `eventoId` = `EVENT_ID`) y los atributos de `data-model.md` §1: `aperturaLocal` (`2026-11-19T00:00`), `cierreLocal`, `disponibleHastaLocal`, `cierreAnticipadoEn`, `cierreAnticipadoPor`, `maxArchivosPorSesion` (300), `maxBytesPorSesion` (5368709120), `archivosTotal`, `fotos`, `videos`, `ultimaSubidaEn`. Sin caché (se lee en cada petición que evalúa la ventana)
- [x] T035 **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/recepcion/VentanaRecepcion.java` (servicio con `Clock` inyectable): estado `NO_INICIADA` si ahora < apertura; `CERRADA` si ahora ≥ cierre o hay `cierreAnticipadoEn`; `ABIERTA` en otro caso, con `ZoneId.of("America/Bogota")` sobre los textos locales; `cierreEfectivo()`; `requireAbierta()` que lanza `RECEPCION_NO_INICIADA` o `RECEPCION_CERRADA`; `periodoTerminado()` con `disponibleHastaLocal`. Marcas de tiempo de archivos y sesiones en UTC. Depende de T034 y T032
- [x] T036 **[CÓDIGO]** Ampliar `backend/src/main/java/co/boda/fotos/config/SecurityConfig.java`: `permitAll` para `/actuator/health`, `/api/recepcion/estado`, `/api/sesiones-subida`, `/api/subidas/**` (la identidad es la sesión de subida, se valida en el servicio) y `POST /api/admin/login`; `/api/admin/**` autenticado; respuestas 401/403 en el formato JSON `NO_AUTENTICADO` de T032. El filtro Bearer se agrega en T070

---

## Fase 3: Historia de usuario 1 - Invitado sube fotos y videos desde el QR (Prioridad: P1) 🎯 MVP

**Plan**: backend y subida simple el Dom 15; multipart y miniaturas el Lun 16.

**Objetivo**: un invitado abre el QR, escribe su nombre y sube fotos y videos directo a S3 con progreso por archivo y reintento solo de los fallidos (FR-001 a FR-012a, FR-018 a FR-022, FR-026a).

**Prueba independiente**: con la recepción abierta, desde un iPhone (Safari) y un Android (Chrome) en red móvil, escanear el QR, subir varias fotos y un video grande y confirmar que quedan en `boda-fotos` como `COMPLETADO` con el nombre del invitado (`quickstart.md` §4.1 y §4.2).

### Backend

- [x] T037 [P] [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/FormatoArchivo.java`: tabla de `data-model.md` §2 — `jpg` (`.jpg`/`.jpeg`) `image/jpeg`, `png` `image/png`, `heic` `image/heic`, `webp` `image/webp` (FOTO, máx. 41.943.040 B) y `mp4` `video/mp4`, `mov` `video/quicktime` (VIDEO, máx. 524.288.000 B); tamaño 0 → `ARCHIVO_VACIO`; la extensión manda y un `tipoContenido` que la contradice se rechaza; verificación de firma sobre los primeros 16 bytes (JPEG `FF D8 FF`, PNG `89 50 4E 47`, WEBP `RIFF....WEBP`, HEIC/MP4/MOV caja `ftyp`)
- [x] T038 [P] [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/SesionSubida.java` y `SesionSubidaRepository.java` sobre `boda-sesiones`: ítem `tipo = SUBIDA`, `id` = `sub#<128 bits aleatorios en Base64URL>`, `eventoId`, `ultimoNombre`, `archivos`, `bytes`, `creadaEn` (UTC), `expiraEnEpoch` = cierre de la recepción + 1 día; tope atómico con `UpdateItem` condicional `ADD archivos :1, bytes :tamano` con condición `archivos < :maxArchivos AND bytes <= :maxBytesMenosTamano` (si falla → `TOPE_SESION`)
- [x] T039 [P] [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/ArchivoMultimedia.java` y `ArchivoRepository.java` sobre `boda-fotos`: atributos de `data-model.md` §2 (`photoId` UUID v4, `eventoId`, `sesionId`, `nombreInvitado` "1–60 caracteres tras recortar espacios, sin caracteres de control (FR-004)", `nombreOriginal` hasta 255 caracteres, `tipo`, `formato`, `contentType`, `tamano`, `modo`, `uploadId`, `numeroPartes`, `claveS3`, `claveMiniatura`, `estado`, `autorizadoEn`, `subidoEn`); crear `PENDIENTE`; transición `PENDIENTE → COMPLETADO` con `TransactWriteItems` de **dos `Update`** (archivo con condición `estado = PENDIENTE`, que pone `galeriaEvento = eventoId` y `galeriaOrden = <subidoEn>#<photoId>`; y evento con `ADD archivosTotal`, `fotos`/`videos` y `SET ultimaSubidaEn`) sin `ConditionCheck`; `PENDIENTE → DESCARTADO`; completar es idempotente (un `COMPLETADO` responde éxito sin volver a sumar)
- [x] T040 [P] [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/AlmacenamientoS3.java`: claves `eventos/<eventoId>/originales/<archivoId>.<ext>` y `eventos/<eventoId>/miniaturas/<archivoId>.jpg` (UUID generado por el backend, nombre original solo en DynamoDB); `PUT` prefirmado con `Content-Type` **y `Content-Length` firmados (tamaño declarado)** y vigencia de 5 minutos (spike R2: S3 responde 403 si el cuerpo tiene otro tamaño; confirmado con `S3Presigner` del SDK de Java: `contentLength` queda firmado en el `PUT` y en `UploadPart`, de 10 MiB y la última con el resto; el presigner usa el endpoint regional forzado en `AwsConfig.java`); `CreateMultipartUpload`, URL prefirmada de `UploadPart`, `CompleteMultipartUpload`, `AbortMultipartUpload`; `HeadObject`, `GetObject` con `Range` de 16 bytes y `DeleteObject`; `GetObject` prefirmado de 15 minutos. `HeadObject` sobre clave inexistente puede dar 403: tratar 403 y 404 como "no existe" (research R2)
- [x] T041 [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/SesionSubidaService.java` y `SesionSubidaController.java`: `POST /api/sesiones-subida` (`{"nombre"}` → 201 con `sesionId`, `nombre`, `maxArchivos`, `maxBytes` según `boda-evento`); `NOMBRE_INVALIDO` si está vacío, son solo espacios o pasa de 60 caracteres; exige recepción abierta (T035). Depende de T038
- [x] T042 [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/SubidaService.java` con `autorizar`: valida en este orden sesión (`X-Sesion-Subida`, si no `SESION_INVALIDA`), recepción abierta, nombre, formato por extensión, tamaño (0 → `ARCHIVO_VACIO`; > 40 MB foto o > 500 MB video → `ARCHIVO_MUY_GRANDE`), tope de sesión; modo `MULTIPART` si es VIDEO y `tamano ≥ 104857600`, si no `SIMPLE`; crea el ítem `PENDIENTE`; devuelve URL firmada (o `tamanoParte`/`numeroPartes`) y `urlMiniatura` si `conMiniatura` (la miniatura no cuenta para el tope). Además `nuevaUrl` (reintento sin sumar al tope) y `partes` (**máximo 3 números por petición**, rango 1..`numeroPartes`, si no `PETICION_INVALIDA`). Depende de T037, T038, T039, T040, T035
- [x] T043 [US1] **[CÓDIGO]** Agregar a `SubidaService.java` (`backend/src/main/java/co/boda/fotos/subida/`) `completar` y `cancelar`: en multipart ordenar las partes con sus `ETag` y llamar a `CompleteMultipartUpload`; `HeadObject` para comparar tamaño y `Content-Type` con lo autorizado y leer los primeros 16 bytes para la firma; objeto o partes ausentes → `SUBIDA_INCOMPLETA` (409, reintentable); tamaño, tipo o firma distintos → borrar el objeto y `CONTENIDO_INCONSISTENTE` (422); si `miniaturaSubida` y la miniatura existe con ≤ 1 MB, asociar `claveMiniatura`, si no el archivo queda sin miniatura (nunca falla por la miniatura); ejecutar la transacción de T039; `cancelar` aborta el multipart o borra el objeto de un `PENDIENTE` de esa sesión
- [x] T044 [US1] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/subida/SubidaController.java` con `POST /api/subidas`, `POST /api/subidas/{archivoId}/url`, `POST /api/subidas/{archivoId}/partes`, `POST /api/subidas/{archivoId}/completar` y `DELETE /api/subidas/{archivoId}` (204), con el formato exacto de `contracts/api.md`; cada endpoint solo opera sobre archivos de la sesión del encabezado `X-Sesion-Subida` (si no, `NO_ENCONTRADO`). Depende de T042 y T043
- [x] T045 [US1] **[CONSOLA]** Publicar el backend de invitados: `mvn -q package`, `aws s3 cp backend/target/app.jar s3://boda-artefactos-248033171214/backend/app.jar` y *instance refresh* con `MinHealthyPercentage=100` y `MaxHealthyPercentage=200` (procedimiento de T013); esperar 1 instancia *healthy*. Para la prueba de hoy crear antes un ítem `boda-prueba` en `boda-evento` con la ventana abierta (`quickstart.md` §2) o abrir la de `boda-2026` y restaurarla después (T102)

### Front de invitados

- [x] T046 [P] [US1] **[CÓDIGO]** Crear `front-invitados/src/app/api/api.ts`: cliente tipado del API de invitados (estado, crear sesión, autorizar, `url`, `partes`, `completar`, cancelar), un interceptor que agrega `X-Sesion-Subida` y el mapeo de los errores `{codigo, mensaje}` a una clase `ApiError` cuyo `mensaje` se muestra tal cual al usuario
- [x] T047 [P] [US1] **[CÓDIGO]** Crear `front-invitados/src/app/subida/tipos.ts`: tipo por **extensión** cuando `file.type` viene vacío (HEIC y MOV en Android), límites de 40 MB y 500 MB y mensajes locales idénticos a los de `contracts/api.md` (`TIPO_NO_PERMITIDO`, `ARCHIVO_MUY_GRANDE`, `ARCHIVO_VACIO`); la validación local es solo una ayuda, el servidor decide
- [x] T048 [US1] **[CÓDIGO]** Crear `front-invitados/src/app/subida/sesion.ts`: guarda `boda.sesionId` y `boda.nombre` en `localStorage` (FR-004a), crea la sesión con `POST /api/sesiones-subida` al primer intento de subir y, si el API responde `SESION_INVALIDA`, borra lo guardado y crea una sesión nueva con el nombre actual. Depende de T046
- [x] T049 [US1] **[CÓDIGO]** Crear `front-invitados/src/app/subida/cola-subida.ts`: cola de archivos con **hasta 3 en paralelo**; cada archivo se autoriza (`POST /api/subidas`) **cuando le llega el turno**, no al elegirlo; `PUT` simple con `XMLHttpRequest` (`xhr.upload.onprogress`, como en el spike T027; `fetch` no informa el progreso de subida), con el `Content-Length` firmado y, ante un 403, **una sola** URL nueva por archivo antes de marcarlo fallido (research R2) y los `encabezados` indicados; estados `En cola`, `Subiendo N %`, `Listo ✓`, `Falló: <mensaje>`, `Rechazado: <mensaje>`; si el `PUT` responde 403 por URL vencida pide otra con `POST /api/subidas/{archivoId}/url`; llama a `completar` con reintentos; "Reintentar fallidos" reenvía solo los fallidos (los rechazados por regla no se reintentan). Depende de T046, T047 y T048
- [x] T050 [US1] **[CÓDIGO]** Crear `front-invitados/src/app/subida/subida.ts` (con su `.html` y `.css`, móvil primero): aviso de uso de las fotos arriba ("Las fotos y videos que subas solo los verán Mara y Daniel en su galería privada. No se publican ni se comparten."), campo de nombre prellenado con validación de 1 a 60 caracteres, botón "Elegir fotos y videos" con `<input type="file" multiple accept="image/*,video/mp4,video/quicktime">` (**sin `image/heic` en el `accept`**, para que iOS convierta las fotos HEIC a JPEG y haya miniatura; research R13; verificar con una foto HEIC real en Safari, `quickstart.md` §4.1), lista con barra de progreso por archivo, "Reintentar fallidos", el aviso "Mantén esta pantalla abierta hasta que terminen las subidas." y el cierre "¡Listo! Subiste N archivos. Puedes elegir más cuando quieras." Depende de T049
- [x] T051 [US1] **[CÓDIGO]** Reemplazar en `front-invitados/src/app/app.ts` la pantalla de estado de T018 por el componente de T050 (la pantalla solo se muestra con la recepción abierta: ver T062); mantener `#spike` hasta T094
- [x] T052 [US1] **[MANUAL]** Con el backend de T045, completar el *spike* de R1 a través del dominio de Amplify: `POST /api/sesiones-subida` con cuerpo JSON devuelve 201 y el `sesionId`; `POST /api/subidas` con `X-Sesion-Subida` llega al backend con encabezado y cuerpo; un `DELETE` devuelve 204. Si algo no se reenvía, activar el plan B de R1. Registrar en T029. Resultado (2026-11-14, producción): `POST /api/sesiones-subida` 201, `POST /api/subidas` con `X-Sesion-Subida` 201 y `DELETE /api/subidas/{id}` 204; cuerpo, encabezado y DELETE se reenvían bien y el plan B de R1 no se necesita. La prueba destapó que `GET /api/recepcion/estado` seguía con la respuesta fija de T006 (`NO_INICIADA` con `aperturaLocal` ya cambiada a 2026-11-14T00:00) mientras `POST /api/sesiones-subida` veía la recepción abierta: corregido en T059
- [ ] T053 [US1] **[MANUAL]** Salida del domingo: desde 2 celulares reales subir fotos por el dominio de Amplify y comprobar en `boda-fotos` que quedan `COMPLETADO` con `nombreInvitado`, `tamano` y `claveS3` bajo `eventos/<id>/originales/` (`quickstart.md` §4.1). Anotar fallos de iOS/Android para corregir Resultado parcial (2026-11-14, producción): desde un iPhone por 4G, 3 fotos (hasta 10 MB) y 1 video `.mov` de 17 MB quedaron `COMPLETADO` con sus originales en S3 y los contadores del evento en 4/3/1 (`archivosTotal`/`fotos`/`videos`); el reintento tras activar el modo avión a mitad del video funcionó. Sin miniaturas porque falta T055. Pendiente: Android (el criterio pide 2 celulares reales) y anotar nombre y tipo de una foto HEIC real (`quickstart.md` §4.1)

### Multipart y miniaturas (Lun 16)

- [x] T054 [US1] **[CÓDIGO]** Crear `front-invitados/src/app/subida/multipart.ts`: videos ≥ 100 MB en partes de **10 MiB**, hasta **3 en paralelo**, pidiendo `POST /api/subidas/{archivoId}/partes` **por parte y justo antes de enviarla** (máximo 3 números por petición, nunca todas al inicio); si el `PUT` de una parte da 403 por URL vencida, pedir otra URL y reintentar sin contarlo como fallo; leer el `ETag` de cada respuesta; reintento por parte con espera creciente; al reintentar un archivo no reenviar las partes ya subidas (el progreso no vuelve a 0); `completar` con las partes ordenadas. Depende de T049
- [x] T055 [P] [US1] **[CÓDIGO]** Crear `front-invitados/src/app/subida/miniatura.ts`: miniatura JPEG de lado mayor 480 px y calidad 0,7 (30–80 KB): para video con `<video muted playsinline>` y object URL, buscando el segundo 1 (o el 0 si dura menos); para JPG, PNG y WEBP con `createImageBitmap` y `<canvas>`; **HEIC no se intenta**; tiempo máximo 5 s y, si falla o tarda, el archivo se sube sin miniatura (FR-026a, nunca se bloquea la subida)
- [x] T056 [US1] **[CÓDIGO]** Integrar T054 y T055 en `front-invitados/src/app/subida/cola-subida.ts`: `conMiniatura` y `tamanoMiniatura` (bytes de la miniatura, que se genera **antes** de autorizar) en la autorización, `PUT` de la miniatura a `urlMiniatura` (`Content-Type: image/jpeg`, ≤ 1 MB) **después** del original y `miniaturaSubida` en `completar`. **Hecho (2026-11-14), con un ajuste**: la miniatura sube justo después de autorizar, antes del original, porque su URL de 5 minutos no se renueva y un video grande tarda más (research R4)
- [ ] T057 [US1] **[MANUAL]** Salida del lunes: un video de 300 MB por red móvil con corte de red y reanudación, y un corte de más de 5 minutos en modo avión sin que falle la subida (`quickstart.md` §4.2); confirmar que la miniatura aparece en `eventos/<id>/miniaturas/`
- [x] T058 [P] [US1] **[CÓDIGO]** (Opcional, el primero que se recorta según el plan) Crear `backend/src/test/java/co/boda/fotos/subida/FormatoArchivoTest.java` (JUnit 5): extensiones permitidas y rechazadas, tamaños límite (40 MB / 500 MB), `tipoContenido` contradictorio y firmas de formato válidas e inválidas

---

## Fase 4: Historia de usuario 2 - La recepción respeta su ventana de tiempo (Prioridad: P1)

**Plan**: Dom 15 (el estado ya existe como esqueleto desde la Fase 1).

**Objetivo**: solo se aceptan archivos del 2026-11-19 00:00 al 2026-11-22 12:00 (`America/Bogota`) o hasta el cierre anticipado, evaluado por el servidor (FR-013 a FR-017).

**Prueba independiente**: con horas de prueba en `boda-evento`, comprobar el mensaje antes de la apertura y después del cierre, y que las solicitudes directas también se rechazan (`quickstart.md` §4.4).

- [x] T059 [US2] **[CÓDIGO]** Reemplazar en `backend/src/main/java/co/boda/fotos/recepcion/RecepcionController.java` la respuesta fija de T006 por el estado real de T035: `estado`, `aperturaEn` y `cierreEn` (efectivo, el anticipado si existe) en UTC y `ahora`. Debe evaluar la ventana con `VentanaRecepcion` (el mismo servicio y la misma lectura sin caché de `boda-evento` que usan los endpoints de subida), en cada petición, para que un cambio en la consola se refleje de inmediato; añadida la prueba en quickstart §4.4
- [x] T060 [US2] **[CÓDIGO]** En `backend/src/main/java/co/boda/fotos/subida/AlmacenamientoS3.java` y `SubidaService.java`, la vigencia de toda URL de subida (simple, partes y miniatura) es `min(5 minutos, cierre efectivo − ahora)` (FR-015); nunca se emiten URLs si la recepción no está abierta
- [x] T061 [US2] **[CÓDIGO]** En `completar` de `backend/src/main/java/co/boda/fotos/subida/SubidaService.java`, **en ambos modos**, si la recepción está cerrada: abortar el multipart o borrar el objeto, marcar `DESCARTADO` y responder `RECEPCION_CERRADA` (FR-016 y escenario 2 de la US4); `url` y `partes` también rechazan con la recepción cerrada. Depende de T043
- [x] T062 [US2] **[CÓDIGO]** Crear `front-invitados/src/app/estado/estado.ts`: al abrir llama a `GET /api/recepcion/estado`; `NO_INICIADA` → "La recepción de fotos y videos abre el jueves 19 de noviembre de 2026." sin selector; `CERRADA` → "La recepción de fotos y videos ya cerró. ¡Gracias por compartir!" sin selector; `ABIERTA` → flujo de subida. Si durante una subida el API responde `RECEPCION_CERRADA`, mostrar ese mensaje
- [ ] T063 [P] [US2] **[CÓDIGO]** (Opcional) Crear `backend/src/test/java/co/boda/fotos/recepcion/VentanaRecepcionTest.java` (JUnit 5, `Clock` fijo): antes de la apertura, justo a las 00:00 y a las 12:00 (hora de Cali), después del cierre, cierre anticipado, y `periodoTerminado` en 2026-12-01 23:59:59 y un segundo después
- [ ] T064 [P] [US2] **[CÓDIGO]** (Opcional) Crear `backend/src/test/java/co/boda/fotos/subida/ExpiracionUrlTest.java` (JUnit 5): la vigencia es 5 minutos lejos del cierre y se recorta cuando faltan menos de 5 minutos
- [ ] T065 [US2] **[MANUAL]** Probar la ventana con horas de prueba en el ítem de `boda-evento` (`quickstart.md` §4.4: antes de la apertura, después del cierre y finalización multipart tras el cierre) y restaurar los valores reales (T102). Un `curl` directo a `POST /api/subidas` fuera de la ventana responde `403 RECEPCION_NO_INICIADA` o `RECEPCION_CERRADA`

---

## Fase 5: Historia de usuario 3 - Novios consultan y descargan el material (Prioridad: P2)

**Plan**: API de administración el Lun 16; front de novios el Mar 17.

**Objetivo**: los novios inician sesión con usuario y contraseña, ven la galería paginada con contador, abren cada archivo y lo descargan (FR-023 a FR-027, FR-030, FR-030b, FR-031).

**Prueba independiente**: con archivos ya subidos, iniciar sesión, recorrer la cuadrícula, abrir una foto y un video y descargarlos (`quickstart.md` §4.5 y §4.6).

### Backend

- [x] T066 [P] [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/admin/AdminRepository.java` sobre `boda-admins`: `GetItem` y `UpdateItem` de `usuario`, `nombreVisible`, `hashContrasena`, `intentosFallidos` y `bloqueadaHasta` (UTC); el backend nunca escribe `hashContrasena`
- [x] T067 [P] [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/admin/SesionAdminRepository.java` sobre `boda-sesiones`: ítem `tipo = ADMIN`, `id` = `adm#<SHA-256 en hex del token>` (el token en claro no se guarda), `usuario`, `creadaEn`, `ultimoUsoEn` (se actualiza como máximo cada 5 minutos) y `expiraEnEpoch` = `ultimoUsoEn` + 8 h; el backend **comprueba** `expiraEnEpoch` en cada petición (el TTL de DynamoDB borra con hasta 48 h de retraso); `DeleteItem` al cerrar sesión
- [x] T068 [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/admin/LoginService.java`: `BCryptPasswordEncoder` con costo **12**; usuario inexistente → comparar contra un hash ficticio para igualar el tiempo; **5 fallos consecutivos bloquean la cuenta 15 minutos** (`intentosFallidos`, `bloqueadaHasta`); mientras esté bloqueada responde sin comprobar la contraseña; en todos los fallos el mismo `CREDENCIALES_INVALIDAS` ("Usuario o contraseña incorrectos. Si fallaste varias veces, espera 15 minutos e inténtalo de nuevo."); éxito reinicia `intentosFallidos` y emite un token de 32 bytes aleatorios en Base64URL. Depende de T066 y T067
- [x] T069 [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/admin/AdminController.java`: `POST /api/admin/login` (200 con `token`, `usuario`, `nombreVisible`, `inactividadMaxSegundos` = 28800) y `POST /api/admin/logout` (204, borra la sesión). Depende de T068
- [x] T070 [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/admin/BearerTokenFilter.java` y registrarlo en `SecurityConfig.java`: `Authorization: Bearer <token>` → SHA-256 → buscar la sesión → verificar `expiraEnEpoch` → renovar; sin sesión válida → 401 `NO_AUTENTICADO`; aplica solo a `/api/admin/**` salvo el login. Depende de T067 y T036
- [x] T071 [P] [US3] **[CÓDIGO]** Agregar el modo `hash-contrasena` en `backend/src/main/java/co/boda/fotos/admin/HashContrasenaCli.java` y hacer que `BodaApplication.java` lo ejecute **sin levantar el servidor web** cuando el primer argumento sea `hash-contrasena`: pide la contraseña dos veces con `System.console().readPassword()` (sin eco) y escribe en la salida el hash bcrypt de costo 12 (`$2a$12$...`); no toca AWS (FR-024, FR-031, research R7)
- [x] T072 [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/galeria/GaleriaService.java` y `GaleriaController.java`: `GET /api/admin/resumen` (`archivosTotal`, `fotos`, `videos`, `ultimaSubidaEn`, estado de la recepción, `disponibleHasta`, `periodoTerminado`) y `GET /api/admin/galeria?cursor=&limite=` con `Query` sobre `galeria-index` (`galeriaEvento = EVENT_ID`, `ScanIndexForward=false`, `limite` de 1 a 48 y 24 por defecto, cursor opaco = `LastEvaluatedKey` codificado), cada elemento con `urlMiniatura` prefirmada de 15 minutos o `null`. Sin `Scan`. Depende de T039, T040 y T070
- [x] T073 [US3] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/galeria/ArchivoUrlController.java`: `GET /api/admin/archivos/{archivoId}/url?uso=ver|descargar` devuelve una URL `GET` prefirmada de 15 minutos del original; para `descargar` agrega `response-content-disposition=attachment; filename*=UTF-8''<nombreOriginal codificado>`; solo `COMPLETADO`, si no `NO_ENCONTRADO`. Depende de T072
- [ ] T074 [US3] **[CONSOLA]** Crear las cuentas de `mara` y `daniel`: generar cada hash en tu equipo con `java -jar backend/target/app.jar hash-contrasena` (T071) y escribir el ítem desde CloudShell con el `put-item` de `plan.md` ("Cuentas de los novios", con `attribute_not_exists(usuario)`); entregar las contraseñas por un canal privado y **nunca** al repo. Documentar el procedimiento de restablecer/desbloquear en `infra/README.md` §3 (T104)
- [ ] T075 [US3] **[CONSOLA]** Publicar el backend con la API de administración (`mvn -q package`, `aws s3 cp`, *instance refresh*, como en T045); depende de T073 y T069
- [ ] T076 [US3] **[MANUAL]** Salida del lunes: por `curl` contra producción, login correcto de `mara` y de `daniel`, mismo mensaje con usuario inexistente y con contraseña mala, bloqueo al 5.º fallo, `resumen`, `galeria` (con `limite=2` y el cursor), `url?uso=ver` y `url?uso=descargar`, y que `GET /api/admin/galeria` sin token da 401 (`quickstart.md` §4.5 y §4.7)

### Front de novios

- [x] T077 [US3] **[CÓDIGO]** Crear el proyecto Angular 21 `front-novios/` (`npx @angular/cli@21 new front-novios --routing --style=css --ssr=false --skip-git`) con `front-novios/proxy.conf.json` (`/api` → `http://localhost:8080`) y comprobar que `npm run build` genera `front-novios/dist/front-novios/browser`. Archivo base: `front-novios/package.json`
- [x] T078 [P] [US3] **[CÓDIGO]** Crear `front-novios/src/app/auth/auth.service.ts` (login, token en `sessionStorage` bajo `boda.token`, logout con `POST /api/admin/logout`), `auth.interceptor.ts` (agrega `Authorization: Bearer` **solo** a `/api/admin/*`, nunca a las URLs de S3; ante 401 borra el token y navega a `/entrar`) y `auth.guard.ts`
- [x] T079 [US3] **[CÓDIGO]** Crear `front-novios/src/app/auth/entrar.ts` (usuario, contraseña y botón "Entrar", muestra el `mensaje` genérico del API) y `front-novios/src/app/app.routes.ts` con `/entrar`, `/` (galería, con guard) y `/archivo/:id`; registrar el interceptor en `front-novios/src/app/app.config.ts`. Depende de T077 y T078
- [x] T080 [US3] **[CÓDIGO]** Crear `front-novios/src/app/galeria/galeria.ts` (con `.html` y `.css`): cabecera "1.240 archivos (1.102 fotos, 138 videos) · Última subida: <fecha y hora de Cali>", banner fijo con la fecha límite ("Puedes ver y descargar el material hasta el martes 1 de diciembre de 2026 a las 11:59 p. m. Después, solo se podrá recuperar pidiéndoselo al responsable técnico."), cuadrícula de 3 columnas en celular con miniatura, nombre del invitado y fecha, carga de 24 en 24 con `IntersectionObserver`; HEIC y videos sin miniatura muestran ícono de tipo; JPG/PNG/WEBP sin miniatura cargan el original con `loading="lazy"`; sin filtros ni búsqueda (FR-034). Depende de T079 y T072
- [x] T081 [US3] **[CÓDIGO]** Crear `front-novios/src/app/archivo/archivo.ts`: vista completa con `<img>` (JPG, PNG, WEBP) o `<video controls playsinline>` (MP4, MOV), mensaje "Este formato no se puede previsualizar aquí; descárgalo para verlo." para HEIC y botón "Descargar original" como **enlace directo** a la URL de `uso=descargar` (sin `fetch`, por eso no hace falta CORS). Depende de T080 y T073
- [x] T082 [US3] **[CÓDIGO]** Agregar a `front-novios/src/app/galeria/galeria.ts` el botón "Cerrar sesión" y el cierre automático al recibir 401 por inactividad de 8 horas (FR-030)
- [ ] T083 [US3] **[CÓDIGO]** Mergear la rama de trabajo del front de novios a `david/release` (solo cuando esté listo; `git merge --no-ff`, push) para que `boda-novios` construya; verificar el build en Amplify y que `https://<dominio-novios>/api/recepcion/estado` responde. Si el 18 no está listo, aplicar la excepción del plan (T101)
- [ ] T084 [US3] **[MANUAL]** Salida del martes: en celular y escritorio, iniciar sesión, recorrer la galería con el contador, abrir una foto y un video, descargar el original de cada uno con su nombre original y comprobar el cierre de sesión (`quickstart.md` §4.5 y §4.6)

---

## Fase 6: Historia de usuario 4 - Novios administran archivos y la recepción (Prioridad: P3)

**Plan**: backend el Lun 16, interfaz el Mar 17.

**Objetivo**: los novios eliminan archivos no deseados y cierran la recepción antes de la hora prevista (FR-028, FR-029).

**Prueba independiente**: con sesión iniciada, eliminar un archivo y verlo desaparecer; cerrar la recepción y comprobar que la página de invitados la muestra cerrada, también tras un reinicio del sistema (`quickstart.md` §4.4 y §4.6).

- [x] T085 [US4] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/galeria/EliminacionService.java` y `DELETE /api/admin/archivos/{archivoId}` (204): borrar original y miniatura de S3 y, en una transacción de dos `Update` sin `ConditionCheck`, pasar el archivo a `ELIMINADO` (condición `estado = COMPLETADO`, `eliminadoEn`, `eliminadoPor`, `REMOVE galeriaEvento, galeriaOrden`) y restar 1 a `archivosTotal` y a `fotos`/`videos` en el evento; sin papelera; si ya no existe → `NO_ENCONTRADO`. Depende de T039 y T070
- [x] T086 [US4] **[CÓDIGO]** Crear `backend/src/main/java/co/boda/fotos/recepcion/CierreAnticipadoService.java` y `POST /api/admin/recepcion/cerrar`: `UpdateItem` sobre `boda-evento` con `SET cierreAnticipadoEn = <UTC>, cierreAnticipadoPor = <usuario>` y condición `attribute_not_exists(cierreAnticipadoEn)` (idempotente, sin reapertura); responde `{estado, cierreEn, cerradaPor}`; efecto inmediato porque T035 lee el ítem en cada petición. Depende de T035 y T070
- [x] T087 [US4] **[CÓDIGO]** Agregar a `front-novios/src/app/archivo/archivo.ts` el botón "Eliminar" con la confirmación "¿Eliminar este archivo? No se puede recuperar."; si el API responde `NO_ENCONTRADO` (el otro novio ya lo eliminó) mostrar su mensaje y volver a la galería. Depende de T085
- [x] T088 [US4] **[CÓDIGO]** Agregar a `front-novios/src/app/galeria/galeria.ts` el estado de la recepción y el botón "Cerrar la recepción ahora" con la confirmación "¿Cerrar la recepción? Los invitados ya no podrán subir más fotos ni videos. Esto no se puede deshacer.". Depende de T086
- [ ] T089 [US4] **[MANUAL]** Probar eliminar (el archivo desaparece y el contador baja) y el cierre anticipado (la página de invitados muestra "cerrada"; reiniciar con *instance refresh* y comprobar que sigue cerrada); quitar después `cierreAnticipadoEn` y `cierreAnticipadoPor` de `boda-2026` (T102) — `quickstart.md` §4.4 y §4.6

---

## Fase 7: Historia de usuario 5 - El material deja de estar disponible al terminar el periodo (Prioridad: P3)

**Plan**: Mar 17 (junto con el front de novios).

**Objetivo**: después del 2026-12-01 23:59 (Cali) la galería no muestra ni permite descargar, pero **nada se borra automáticamente** (FR-032, FR-033; FR-032 no cambió).

**Prueba independiente**: poner `disponibleHastaLocal` en el pasado y comprobar que la galería informa que el periodo terminó, mientras el material sigue en S3.

- [x] T090 [US5] **[CÓDIGO]** En `backend/src/main/java/co/boda/fotos/galeria/GaleriaService.java` y `ArchivoUrlController.java`, responder `410 PERIODO_TERMINADO` en `galeria` y en `url` cuando `VentanaRecepcion.periodoTerminado()` (T035) sea verdadero; `resumen` sigue funcionando con `periodoTerminado: true`. No se agrega ningún borrado automático (FR-033)
- [x] T091 [US5] **[CÓDIGO]** En `front-novios/src/app/galeria/galeria.ts`, mostrar en vez de la cuadrícula "El periodo para ver y descargar terminó el 1 de diciembre de 2026. Para recuperar el material, escribir al responsable técnico." cuando el API responde `PERIODO_TERMINADO` o, por fecha y hora de Cali, si el backend ya está apagado (después del 2026-12-02)
- [ ] T092 [US5] **[MANUAL]** Poner `disponibleHastaLocal` en el pasado en `boda-2026`, comprobar el mensaje y que los objetos siguen en S3 (sin borrado automático; el bucket solo tiene la regla de multipart incompletos), y restaurar el valor `2026-12-01T23:59:59` (T102) — `quickstart.md` §4.9

---

## Fase 8: Verificación, restauración y cierre (Mar 17 tarde – Mié 18; después del evento)

**Propósito**: ejecutar la lista de chequeo de la constitución en producción, restaurar la configuración real y dejar `infra/README.md` completo.

**⚠ La lista de chequeo se ejecuta completa en producción antes del 2026-11-19 (SC-008).**

- [ ] T093 **[CONSOLA]** Cuando el backend esté sano, activar las acciones de las alarmas: `aws cloudwatch enable-alarm-actions --alarm-names boda-backend-sin-salud boda-api-errores-5xx` (plan, recurso 10). Confirmar que la suscripción del tema `boda-alertas` está confirmada
- [ ] T094 **[CÓDIGO]** Eliminar la pantalla temporal `front-invitados/src/app/spike.ts` y su referencia en `front-invitados/src/app/app.ts` (de T027); merge a `david/release`. **Código hecho (2026-11-14, rama `daniel/001-admin`); falta el merge a `david/release`**
- [ ] T095 **[CONSOLA]** Publicar la versión final del backend (`mvn -q package`, `aws s3 cp`, *instance refresh*) y verificar `boda-backend-tg` sano y `GET /api/recepcion/estado` con el estado real
- [ ] T096 **[MANUAL]** Lista de chequeo `quickstart.md` §4.0 a §4.3: abrir la ventana, subida desde iPhone/Safari y Android/Chrome con red móvil (con un video ≥ 100 MB), reintento sin repetir los exitosos, rechazo de tipos y tamaños, y el tope de sesión (`maxArchivosPorSesion = 3` temporal)
- [ ] T097 **[MANUAL]** Lista de chequeo `quickstart.md` §4.4: antes de la apertura, después del cierre, multipart tras el cierre y cierre anticipado persistente tras reiniciar
- [ ] T098 **[MANUAL]** Lista de chequeo `quickstart.md` §4.5 y §4.6: login de ambas cuentas, mensaje genérico, bloqueo por intentos, galería, descarga y eliminación
- [ ] T099 **[MANUAL]** Lista de chequeo `quickstart.md` §4.7 y §4.8: objeto de S3 inaccesible sin URL prefirmada, URL vieja vencida, API de administración sin token, ALB inaccesible directo y alerta por correo al detener `boda.service` (llega en menos de 10 min)
- [ ] T100 **[MANUAL]** Probar una foto y un video en Samsung Internet si hay un equipo disponible (FR-002, `quickstart.md` §4.1); es el primer recorte del plan si no lo hay
- [ ] T101 **[CONSOLA]** Decidir el 18 por la noche si `front-novios` está publicado y verificado. Si **no**: aplicar la excepción del plan (`plan.md`, "Complexity Tracking"): desactivar el *auto build* de `david/release` en `boda-invitados` del 19 al 22, mergear `front-novios` a `david/release` **antes del 2026-11-22 a las 12:00**, repetir `quickstart.md` §4.5 y §4.6 desde la interfaz, y mientras tanto cerrar la recepción a mano editando `cierreAnticipadoEn` y `cierreAnticipadoPor` en `boda-2026`
- [ ] T102 **[CONSOLA]** Restaurar para el evento (`quickstart.md` §4.9, obligatorio, riesgo K5): ítem `boda-2026` con `aperturaLocal = 2026-11-19T00:00`, `cierreLocal = 2026-11-22T12:00`, `disponibleHastaLocal = 2026-12-01T23:59:59`, topes `300` y `5368709120` y sin `cierreAnticipado*`; borrar todas las pruebas con `infra/scripts/limpiar-pruebas.sh` desde CloudShell (primero `./limpiar-pruebas.sh Prueba` para revisar la lista y luego `./limpiar-pruebas.sh --ejecutar Prueba`; borra S3, `boda-fotos`, multipart y sesiones, y recalcula los contadores de `boda-2026`; el resumen debe mostrar `archivosTotal=0  fotos=0  videos=0`) y limpiar `eventos/boda-prueba/` si se usó ese evento; quitar `http://localhost:4200` del CORS; activar PITR en `boda-fotos`; comprobar que el bucket solo tiene la regla de multipart incompletos (FR-021, FR-033)
- [x] T103 [P] **[CÓDIGO]** Actualizar `README.md`: estructura del repositorio con `backend/`, `front-invitados/`, `front-novios/`, `amplify.yml` e `infra/`; "Estado actual" con plan, tareas e implementación; flujo de ramas `david/...` → `david/release`
- [ ] T104 **[CÓDIGO]** Cierre de `infra/README.md`: cuentas de los novios y procedimiento de restablecer contraseña, alarmas activadas, CORS final, pasos "Orden para recrear desde cero" actualizados con los recursos nuevos, "Pendientes" vaciados y "Última actualización". Depende de T074, T093 y T102
- [ ] T105 **[MANUAL]** Generar el QR con el dominio definitivo de `boda-invitados` (**solo después** de T102; riesgo K6) y comprobar con el escaneo desde 2 celulares que abre el formulario con el mensaje "abre el jueves 19 de noviembre"
- [ ] T106 **[MANUAL]** Después del cierre del 22: buscar en `boda-fotos` los ítems `PENDIENTE` de modo `SIMPLE` cuyo objeto exista en S3 (finalización que no llegó) y mostrárselos a los novios (`quickstart.md` §5)
- [ ] T107 **[CONSOLA]** El **2026-12-02** apagar el cómputo según la constitución: eliminar `boda-backend-asg` y `boda-alb` (y sus IPv4); conservar CloudFront, las tablas, Amplify y el bucket (FR-033, SC-009). Registrar en `infra/README.md` el estado final

---

## Dependencias y orden de ejecución

### Dependencias entre fases

- **Fase 1** no depende de nada y empieza hoy. Dentro de 1A el orden es obligatorio: T007 → T008 → T009 → T010 → T011. El ASG no se crea hasta que el `.jar` esté en el bucket.
- **Fase 2** depende solo del esqueleto de 1A (`SecurityConfig.java`, `application.yml`) y **bloquea** las historias.
- **Fases 3 a 7** dependen de la Fase 2; se entregan en orden de prioridad: US1 → US2 → US3 → US4 → US5.
- **Fase 8** depende de las historias que se vayan a verificar.

### Dependencias entre historias

- **US1 (P1)**: empieza tras la Fase 2; no depende de otras historias. Es el MVP.
- **US2 (P1)**: modifica archivos que crea US1 (`SubidaService.java`, `AlmacenamientoS3.java`, `RecepcionController.java`); se hace justo después de la subida simple de US1, el mismo domingo.
- **US3 (P2)**: reutiliza `ArchivoRepository.java` y `AlmacenamientoS3.java` de US1 (la galería lee lo que subió US1) y el filtro Bearer de T070; el front de novios (`front-novios/`) es independiente del de invitados.
- **US4 (P3)** y **US5 (P3)**: dependen del filtro Bearer y de la galería de US3.
- Las historias no son totalmente independientes en el código (comparten repositorios del backend), pero cada una se puede **probar** por separado con su criterio de la fase.

### Dentro de cada historia

- Backend: repositorios y utilidades → servicios → controladores → publicación (`[CONSOLA]`) → prueba (`[MANUAL]`).
- Front: cliente del API y utilidades → servicios y cola → pantalla → integración.
- Una prueba `[MANUAL]` cierra cada historia; el criterio de salida está en `plan.md` ("Calendario y alcance").

## Ejemplos de trabajo en paralelo

```text
# Fase 1A, tras crear el pom (T001):
T002  .gitignore
T003  BodaApplication.java
T004  application.yml

# Fase 1C (en paralelo con la espera del ASG):
T017  front-invitados/proxy.conf.json
T018  front-invitados/src/app/app.ts
T019  amplify.yml

# Fase 3 (US1), repositorios y utilidades del backend, archivos distintos:
T037  FormatoArchivo.java
T038  SesionSubidaRepository.java
T039  ArchivoRepository.java
T040  AlmacenamientoS3.java

# Fase 3 (US1), front en paralelo con el backend:
T046  api.ts
T047  tipos.ts
T055  miniatura.ts
```

## Estrategia de implementación

### Calendario (de `plan.md`)

| Día | Tareas |
| --- | --- |
| Sáb 14 | Fase 1 completa (T001 a T029): backend mínimo en el ASG, rama `david/release`, Amplify, CORS y *spikes* de R1 y R2 |
| Dom 15 | Fase 2, US1 (backend y subida simple: T037 a T053) y US2 (T059 a T065) |
| Lun 16 | US1 multipart y miniaturas (T054 a T058), API de administración de US3 (T066 a T076), US4 backend (T085, T086) |
| Mar 17 | Front de novios (T077 a T084), US4 y US5 de interfaz, alarmas (T093), primera pasada de la lista de chequeo |
| Mié 18 | Fase 8: lista de chequeo completa en producción, restaurar (T102), QR (T105) y decisión sobre la excepción (T101) |
| Después del 22 y el 2 de dic | Huérfanos (T106) y apagado del cómputo (T107) |

### MVP primero (US1 + US2)

1. Fase 1 y Fase 2.
2. US1: la subida simple del domingo, luego multipart y miniaturas.
3. US2 el mismo domingo.
4. **Parar y validar** con 2 celulares reales (T053 y T057) antes de seguir con los novios.

### Si hay retraso (orden del plan)

1. Prueba en Samsung Internet (T100).
2. Pruebas unitarias (T058, T063, T064).
3. Si el front de novios no está listo el 18: excepción aprobada, publicar antes del 2026-11-22 a las 12:00 (T101). Las miniaturas, el multipart, las validaciones del servidor y el login seguro **no se recortan**.

## Notas

- `[P]` solo aparece donde los archivos son distintos; todas las tareas que editan `infra/README.md` son secuenciales.
- Las tablas, el GSI, el bucket de artefactos, la política v3 y el ítem `boda-2026` ya están creados: solo tienen tareas de documentación (T012 a T014). Las tareas `[CONSOLA]` de creación o cambio son la launch template v3, el ASG, las dos apps de Amplify, el CORS, las cuentas de los novios, las alarmas y la restauración final.
- El `.jar` mínimo no necesita el ítem `boda-2026`; el backend completo sí (ya existe).
- El *instance refresh* con `MinHealthyPercentage=100` y `MaxHealthyPercentage=200` evita cortes al publicar una versión nueva del `.jar`.
- Confirmar cada tarea `[CONSOLA]` en `infra/README.md` antes de usar el recurso (Principio V: un recurso sin documentar es un defecto).

---

## Resumen de tareas

| Fase | Tareas |
| --- | --- |
| Fase 1: Preparación y backend mínimo en producción (Sáb 14 de noviembre) | 29 |
| Fase 2: Fundamentos del backend (Dom 15, mañana) | 7 |
| Fase 3: Historia de usuario 1 - Invitado sube fotos y videos desde el QR (Prioridad: P1) 🎯 MVP | 22 |
| Fase 4: Historia de usuario 2 - La recepción respeta su ventana de tiempo (Prioridad: P1) | 7 |
| Fase 5: Historia de usuario 3 - Novios consultan y descargan el material (Prioridad: P2) | 19 |
| Fase 6: Historia de usuario 4 - Novios administran archivos y la recepción (Prioridad: P3) | 5 |
| Fase 7: Historia de usuario 5 - El material deja de estar disponible al terminar el periodo (Prioridad: P3) | 3 |
| Fase 8: Verificación, restauración y cierre (Mar 17 tarde – Mié 18; después del evento) | 15 |
| **Total** | **107** |

| Tipo | Cantidad | IDs |
| --- | --- | --- |
| CÓDIGO | 74 | T001, T002, T003, T004, T005, T006, T007, T012, T013, T014, T015, T016, T017, T018, T019, T020, T025, T027, T029, T030, T031, T032, T033, T034, T035, T036, T037, T038, T039, T040, T041, T042, T043, T044, T046, T047, T048, T049, T050, T051, T054, T055, T056, T058, T059, T060, T061, T062, T063, T064, T066, T067, T068, T069, T070, T071, T072, T073, T077, T078, T079, T080, T081, T082, T083, T085, T086, T087, T088, T090, T091, T094, T103, T104 |
| CONSOLA | 15 | T008, T009, T010, T021, T022, T024, T026, T045, T074, T075, T093, T095, T101, T102, T107 |
| MANUAL | 18 | T011, T023, T028, T052, T053, T057, T065, T076, T084, T089, T092, T096, T097, T098, T099, T100, T105, T106 |
