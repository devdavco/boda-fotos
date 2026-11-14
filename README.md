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

## Stack previsto

- **Backend:** Java + Spring Boot (Maven) en EC2 detrás de un ALB, publicado con HTTPS por CloudFront
  (sin dominio propio ni ACM; CloudFront → ALB va por HTTP y el ALB solo acepta tráfico de CloudFront).
- **Front-ends:** dos apps Angular 21 (invitados y novios) en AWS Amplify Hosting, con despliegue en
  cada push al repo.
- **Datos:** S3 privado (medios) y DynamoDB on-demand (admins, configuración del evento, metadatos).
- **Infraestructura:** creada por la consola de AWS, una sola región (`us-east-1`), con cada recurso
  documentado en el repo para poder recrearlo.

## Estado actual

Se trabaja con [Spec Kit](https://github.com/github/spec-kit)
(`/speckit-specify` → `/speckit-clarify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`).

- [x] Requerimientos de negocio — [`reqs/requerimientos_fotos_boda.md`](reqs/requerimientos_fotos_boda.md)
- [x] Constitución del proyecto v2.0.0 — [`.specify/memory/constitution.md`](.specify/memory/constitution.md)
- [x] Especificación del feature `001-wedding-media-upload` —
  [`specs/001-wedding-media-upload/spec.md`](specs/001-wedding-media-upload/spec.md)
  (checklist de calidad completo; aclaraciones de cuentas admin y retención resueltas)
- [ ] Plan técnico (`/speckit-plan`): estimación de costos
- [ ] Tareas (`/speckit-tasks`)
- [ ] Implementación (backend, front-ends, infraestructura documentada en consola)
- [ ] Lista de chequeo manual ejecutada en producción antes del 2026-11-19

## Estructura del repositorio

```
reqs/       Requerimientos de negocio (fuente de verdad)
specs/      Especificaciones por feature (Spec Kit)
.specify/   Constitución, plantillas y scripts de Spec Kit
.claude/    Skills de Spec Kit para Claude Code
```

Cuando empiece la implementación se agregarán las carpetas del monorepo para backend, cada
front-end e infraestructura.
