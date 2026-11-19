# boda-fotos

Plataforma para que los invitados de la boda de **Mara y Daniel** (21 de noviembre de 2026, Cali,
Colombia) suban fotos y videos desde el celular escaneando un código QR, y para que los novios los
consulten, descarguen y administren desde un front-end privado.

## Fechas clave

| Fecha (hora de Cali, `America/Bogota`) | Hito |
| --- | --- |
| 2026-11-18 | Sistema desplegado en producción y verificado en celulares reales |
| 2026-11-19 00:00 | Abre la recepción de fotos y videos |
| 2026-11-19 → 2026-11-22 | Congelamiento: solo correcciones críticas |
| 2026-11-21 | Boda |
| 2026-11-22 12:00 | Cierra la recepción (los novios pueden cerrarla antes) |
| 2026-12-01 23:59 | Último momento para consultar y descargar el material |
| 2026-12-02 | Sin borrado automático: el material se conserva en el almacenamiento privado hasta nuevo aviso de los novios |

## Alcance (fase 1)

**Invitados** (sin cuenta): abren el QR, leen el aviso de uso de las fotos, escriben su nombre
(1–60 caracteres) y suben varios archivos con progreso por archivo y reintento solo de los fallidos.

- Fotos: JPG, JPEG, PNG, HEIC, WEBP — máx. 40 MB.
- Videos: MP4, MOV — máx. 500 MB; multipart a partir de 100 MB.
- Subida directa del navegador a S3 con URLs prefirmadas; el servidor valida tipo, tamaño y ventana.

**Novios** (front-end separado, usuario y contraseña, 2 cuentas): galería en cuadrícula con
miniaturas, fecha y nombre declarado; vista completa, descarga individual, eliminación y cierre
anticipado de la recepción.

**Fuera de alcance:** cuentas de invitados, comentarios/likes, filtros, reconocimiento facial,
redes sociales y descarga en ZIP.

## Stack

- **Backend** (`backend/`): Java 25 (Corretto), Spring Boot 4.1, Maven y AWS SDK for Java v2. Un
  solo `.jar` en una EC2 `t3.micro` (Auto Scaling de 1 instancia) detrás de un ALB, publicado con
  HTTPS por CloudFront. El mismo `.jar` tiene el comando `hash-contrasena` para crear las cuentas
  de los novios.
- **Front-ends**: dos apps Angular 21 en AWS Amplify Hosting, con despliegue en cada push a
  `david/release`. Cada una reenvía `/api/*` al backend con un *rewrite* de Amplify.
  - `front-invitados/`: página del QR. Sube directo a S3 con URLs prefirmadas, en partes de
    10 MiB para videos de 100 MB o más, y con miniaturas generadas en el celular.
  - `front-novios/`: login, galería con scroll infinito, vista completa, descarga, eliminación y
    cierre anticipado de la recepción.
- **Datos:** S3 privado `boda-fotos-2026` (originales y miniaturas) y DynamoDB on-demand
  (`boda-fotos` con el índice `galeria-index`, `boda-evento`, `boda-admins`, `boda-sesiones`).
- **Infraestructura:** creada por la consola de AWS en `us-east-1`, cada recurso documentado en
  [`infra/README.md`](infra/README.md) para poder recrearlo.

```text
Celular ── https://<app>.amplifyapp.com ──(rewrite /api/*)──> CloudFront ──> ALB ──> EC2 (Spring Boot)
                                                                                    ├─ DynamoDB
                                                                                    └─ firma URLs de S3
Celular ── PUT / GET directo con URL prefirmada ──────────────────────────────────> S3 (privado)
```

## Estado actual

Se trabaja con [Spec Kit](https://github.com/github/spec-kit)
(`/speckit-specify` → `/speckit-clarify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`).
Todo el diseño está en [`specs/001-wedding-media-upload/`](specs/001-wedding-media-upload/).

- [x] Requerimientos de negocio — [`reqs/requerimientos_fotos_boda.md`](reqs/requerimientos_fotos_boda.md)
- [x] Constitución del proyecto v2.0.0 — [`.specify/memory/constitution.md`](.specify/memory/constitution.md)
- [x] Especificación — [`spec.md`](specs/001-wedding-media-upload/spec.md)
- [x] Plan técnico y decisiones — [`plan.md`](specs/001-wedding-media-upload/plan.md),
  [`research.md`](specs/001-wedding-media-upload/research.md),
  [`data-model.md`](specs/001-wedding-media-upload/data-model.md) y
  [`contracts/`](specs/001-wedding-media-upload/contracts/)
- [x] Tareas — [`tasks.md`](specs/001-wedding-media-upload/tasks.md) (107 tareas, un commit por tarea)
- [ ] Implementación: **101 de 107 tareas hechas** (2026-11-18). El código de las cinco historias de
  usuario está completo: subida de invitados con multipart y miniaturas, ventana de recepción, API
  y front de novios, eliminación, cierre anticipado y fin del periodo, y está publicado en
  producción. Faltan el QR, la prueba de Samsung Internet, la revisión
  posterior al evento y el apagado del cómputo.
- [x] Lista de chequeo manual ejecutada en producción antes del 2026-11-19
  ([`quickstart.md`](specs/001-wedding-media-upload/quickstart.md) §4)

## Estructura del repositorio

```
backend/          API Spring Boot (Java 25, Maven); `mvn package` genera target/app.jar
front-invitados/  App Angular de los invitados (página del QR)
front-novios/     App Angular de los novios (galería privada)
amplify.yml       Build de los dos front-ends en Amplify (monorepo, AMPLIFY_MONOREPO_APP_ROOT)
infra/            Inventario de la infraestructura AWS y scripts de operación
local/            Entorno local sin AWS: DynamoDB Local y S3 falso en Docker, scripts y guía
reqs/             Requerimientos de negocio (fuente de verdad)
specs/            Especificaciones por feature (Spec Kit)
.specify/         Constitución, plantillas y scripts de Spec Kit
.claude/          Skills de Spec Kit para Claude Code
```

## Ejecutar localmente

Todo en el equipo, sin tocar AWS: [`local/README.md`](local/README.md). En resumen (PowerShell, con
Docker Desktop abierto):

```powershell
.\local\preparar.ps1                          # DynamoDB Local + S3 falso + tablas + datos de prueba
.\local\backend.ps1                           # http://localhost:8080
cd front-invitados; npm ci; npm start         # http://localhost:4200
cd front-novios; npm ci; npx ng serve --port 4201   # http://localhost:4201 (mara / clave-de-prueba-1)
```

Pruebas unitarias: backend con `mvn test` en `backend/` (formatos, ventana de recepción y vigencia de URLs) y multipart con `npx ng test --watch=false` en `front-invitados/`.

## Ramas y despliegue

| Rama | Uso |
| --- | --- |
| `main` | Rama oficial: constitución, spec e infraestructura aprobadas por PR. |
| `david/001-plan`, `david/001-impl` | Plan, tareas e implementación de David. |
| `daniel/001-admin` | Trabajo de Daniel sobre `david/001-impl`, que se integra por PR a esa rama: API y front de novios, ventana de recepción en el servidor, multipart, miniaturas y entorno local. |
| `david/release` | **Lo que despliega Amplify.** Su dominio es la URL del QR: no se recrea ni se renombra. Solo entra lo que está listo. |

- Daniel integra su trabajo con un **Pull Request de `daniel/001-admin` hacia `david/001-impl`**.
  Publicar es de David: lleva `david/001-impl` a `david/release` con `merge --no-ff`. Del 19 al 22
  de noviembre solo entran correcciones críticas.
- **Backend:** `mvn package`, subir `target/app.jar` a
  `s3://boda-artefactos-248033171214/backend/app.jar` y lanzar un *instance refresh* del ASG, sin
  corte de servicio ([`infra/README.md`](infra/README.md) §13).
- **Front-ends:** push o PR a `david/release`; Amplify construye y publica solo.
- Paso a producción del trabajo de `daniel/001-admin` (backend, cuentas, pruebas, PR y app
  `boda-novios`), con comprobaciones y vuelta atrás: [`infra/paso-a-produccion.md`](infra/paso-a-produccion.md).
