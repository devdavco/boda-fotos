<!--
Sync Impact Report
- Version change: 1.0.0 → 2.0.0 (MAJOR: se redefine el Principio V)
- Enmiendas aprobadas por el profesor el 2026-11-13:
  1. Infraestructura creada por la consola de AWS (no AWS CDK); cada recurso documentado en el
     repo para poder recrearlo.
  2. Front-ends Angular en AWS Amplify Hosting con despliegue en cada push (no S3 + CloudFront
     con OAC).
  3. Región única `us-east-1`.
  4. Sin dominio propio: HTTPS con los dominios de CloudFront (API) y Amplify (front-ends); sin
     certificado ACM en el ALB.
- Principios modificados:
  - II. Privado y seguro por defecto (sitios estáticos y HTTPS)
  - V. "Infraestructura como código y costo acotado" → "Infraestructura documentada y costo
    acotado" (redefinido)
- Secciones modificadas: Restricciones técnicas y stack (cómputo, front-ends, infraestructura);
  Flujo de trabajo y control de calidad (viñeta de commits: se retira `cdk diff`/`cdk deploy`)
- Secciones añadidas / eliminadas: ninguna
- Templates revisados: plan-template.md, spec-template.md, tasks-template.md (leen la
  constitución en tiempo de ejecución; no requieren cambios)
- Impacto en specs/planes existentes: ninguno detectado en la constitución; el plan técnico
  (/speckit-plan) debe partir de us-east-1, Amplify y consola. Resueltas: región definitiva y
  dominio/DNS.
- TODOs diferidos: las preguntas abiertas de negocio (fin exacto del 1 de diciembre, retención
  posterior, número de cuentas admin, recuperación de contraseña) se resuelven en
  /speckit-clarify.
-->

# QR Matrimonio Constitution

Plataforma para que los invitados de la boda de Mara y Daniel (21 de noviembre de 2026, Cali)
suban fotos y videos escaneando un QR, y para que los novios los consulten y descarguen desde un
front-end privado.

## Core Principles

### I. La fecha del evento manda (simplicidad)

- La recepción abre el **2026-11-19**. El sistema completo MUST estar desplegado en producción y
  verificado en celulares reales a más tardar el **2026-11-18**.
- Se implementa lo mínimo que cumple `reqs/requerimientos_fotos_boda.md`. Cualquier componente,
  servicio, librería o abstracción adicional MUST justificarse en el plan (sección de
  complejidad) indicando qué requerimiento lo exige.
- Lo listado como fuera de alcance (ZIP, comentarios, filtros, reconocimiento facial, redes
  sociales, cuentas de invitados) MUST NOT implementarse en la fase 1.
- Ante dos opciones, se elige la que tenga menos piezas móviles y menos riesgo de despliegue.

**Razón:** el plazo es de días y la fecha no se mueve; un sistema simple que funcione el día del
evento vale más que uno completo que no llegue.

### II. Privado y seguro por defecto

- El bucket de medios MUST tener Block Public Access activado y cifrado en reposo; todo acceso
  (subida y lectura) MUST hacerse con URLs prefirmadas de corta duración emitidas por el backend.
- Los front-ends se sirven desde AWS Amplify Hosting, sin buckets públicos propios. Todo el
  tráfico de los navegadores MUST ir por HTTPS, con los dominios de CloudFront (API) y Amplify
  (front-ends).
- El tramo CloudFront → ALB va por HTTP dentro de AWS. Por eso el ALB MUST aceptar tráfico solo
  desde CloudFront (security group limitado al prefix list de CloudFront) y no ser accesible
  directamente desde internet.
- Las contraseñas de administradores MUST almacenarse solo como hash con salt único usando una
  función para contraseñas (Argon2id o bcrypt vía Spring Security). Nunca en texto claro ni con
  cifrado reversible.
- El login MUST limitar intentos repetidos y MUST responder con un mensaje genérico que no revele
  si falló el usuario o la contraseña.
- Los permisos IAM (rol de la instancia EC2) MUST limitarse a las acciones y recursos necesarios
  (prefijo del evento en S3, tablas concretas de DynamoDB). El navegador nunca accede a DynamoDB.
- No se versionan secretos ni credenciales en el repositorio; la instancia usa su rol IAM y los
  valores sensibles viven en SSM Parameter Store o Secrets Manager.
- Las fotos no se publican ni se comparten fuera de la galería privada de los novios.

**Razón:** son fotos personales de invitados; una filtración no tiene vuelta atrás.

### III. El servidor es la autoridad

- La ventana de recepción (apertura 2026-11-19, cierre 2026-11-22 12:00 `America/Bogota`) y el
  cierre anticipado por los administradores MUST evaluarse en el backend. El front-end solo
  refleja el estado.
- Las fechas de apertura y cierre MUST ser configuración (no constantes enterradas en el código)
  y el cierre anticipado MUST persistirse para que sobreviva a reinicios.
- Las marcas de tiempo se almacenan en UTC; las comparaciones de la ventana se hacen con la zona
  IANA `America/Bogota`.
- Antes de emitir cualquier permiso, el backend MUST validar tipo (JPG/JPEG/PNG/HEIC/WEBP,
  MP4/MOV), tamaño (40 MB foto, 500 MB video) y periodo de recepción. La validación del navegador
  es solo para la experiencia de usuario.
- Las claves de objetos en S3 MUST ser generadas por el sistema (sin colisiones ni
  sobrescrituras); el nombre original se guarda solo como metadato.
- Las URLs prefirmadas MUST expirar a más tardar al cierre, y la finalización de cargas multipart
  MUST rechazarse después del cierre.

**Razón:** el cliente no es confiable; las reglas de negocio críticas se garantizan en un solo
lugar.

### IV. Móvil primero y resiliente a redes inestables

- Las interfaces se diseñan primero para celular y MUST funcionar en versiones recientes de
  Chrome, Safari iOS y Samsung Internet, sin instalar nada.
- Los archivos MUST subirse directamente del navegador a S3, nunca a través del backend.
- La subida MUST mostrar progreso por archivo, permitir reintentar solo los fallidos y usar
  multipart para videos de 100 MB o más (reintento por parte).
- El flujo del invitado MUST ser corto: abrir el QR, escribir su nombre, elegir archivos y subir.

**Razón:** los invitados suben desde datos móviles en plena fiesta; cada fricción se traduce en
fotos que nunca llegan.

### V. Infraestructura documentada y costo acotado
- Toda la infraestructura AWS se crea desde la consola de AWS, y cada recurso MUST quedar
  documentado en el repositorio (nombre, configuración relevante y pasos) con detalle suficiente
  para recrearlo. No se usa AWS CDK. Un recurso sin documentar en el repo se considera defecto.
- Todos los recursos se crean en una sola región: `us-east-1`.
- MUST existir una regla de ciclo de vida que aborte las cargas multipart incompletas y una
  política de retención explícita para originales y derivados después del 2026-12-01.
- Se dimensiona para un evento: S3 Standard, DynamoDB on-demand, una instancia EC2 pequeña detrás
  del ALB. No se habilita Transfer Acceleration, NAT Gateway, multi-AZ de cómputo ni servicios
  adicionales sin una justificación de costo/beneficio en el plan.
- Antes del despliegue MUST existir una estimación de costo (almacenamiento, solicitudes,
  transferencia de descargas, EC2 y ALB). Después del evento la infraestructura de cómputo se
  apaga o destruye.

**Razón:** es un sistema de vida corta; debe costar poco, poder recrearse rápido y no dejar
cargos residuales.

### VI. Español y respeto por el invitado

- Toda la interfaz, mensajes de error y textos visibles MUST estar en español.
- La página de invitados MUST mostrar un aviso claro sobre el uso de las fotos.
- El nombre del invitado se presenta como dato declarado, nunca como identidad verificada.
- Los mensajes de error MUST ser comprensibles para personas no técnicas e indicar qué hacer
  (reintentar, archivo muy grande, recepción cerrada).

**Razón:** los usuarios son familiares y amigos de todas las edades, no usuarios técnicos.

## Restricciones técnicas y stack

- **Backend:** Java con Spring Boot, build con Maven. Una sola aplicación que expone la API de
  invitados (estado de recepción, emisión de URLs prefirmadas, multipart, finalización) y la API
  de administración (login, listado, descarga, eliminación, cierre anticipado).
- **Cómputo:** Amazon EC2 detrás de un Application Load Balancer. Sin dominio propio ni certificado
  ACM: el HTTPS lo termina CloudFront con su dominio, y el ALB escucha en HTTP:80 solo para
  CloudFront. El ALB hace health checks contra un endpoint de salud de la aplicación.
- **Front-ends:** dos aplicaciones Angular 21 independientes (invitados y novios), desplegadas
  por separado en AWS Amplify Hosting desde el repositorio, con despliegue en cada push. No
  comparten despliegue ni rutas públicas.
- **Datos:** Amazon S3 para los medios (bucket privado, organizado por evento) y Amazon DynamoDB
  para usuarios administradores, configuración del evento y metadatos de archivos.
- **Infraestructura:** consola de AWS en `us-east-1`, con cada recurso documentado en el repo.
- **Repositorio:** monorepo con carpetas separadas para backend, cada front-end e infraestructura.
- Agregar un lenguaje, framework o servicio AWS no listado aquí requiere justificarlo en el plan
  según el Principio I.

## Flujo de trabajo y control de calidad

- Cada funcionalidad sigue el flujo Spec Kit: `/speckit-specify` → `/speckit-clarify` →
  `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`. El plan MUST incluir el
  "Constitution Check" contra estos principios.
- **Pruebas:** las pruebas automatizadas son opcionales. La verificación principal es manual y
  MUST seguir una lista de chequeo documentada en la carpeta del feature que cubra como mínimo:
  - subida de fotos y videos (incluido un video ≥ 100 MB) desde iPhone/Safari y Android/Chrome
    con red móvil real;
  - reintento de un archivo fallido sin repetir los exitosos;
  - rechazo de tipos no permitidos y de archivos sobre el límite;
  - comportamiento antes de la apertura, después del cierre y tras el cierre anticipado;
  - login correcto, login fallido con mensaje genérico y bloqueo por intentos repetidos;
  - que un objeto de S3 no sea accesible sin URL prefirmada.
- La lista de chequeo MUST ejecutarse completa en producción antes del 2026-11-19.
- **Congelamiento:** del 2026-11-19 al 2026-11-22 solo se despliegan correcciones de defectos
  que impidan subir o consultar archivos.
- Los commits son pequeños y descriptivos; los cambios de infraestructura se documentan
  en el repo antes de aplicarlos en la consola.

## Governance

- Esta constitución prevalece sobre cualquier otra práctica del proyecto. Los planes, tareas e
  implementaciones que la contradigan MUST corregirse o justificar la excepción en la sección de
  complejidad del plan.
- **Enmiendas:** se proponen editando este archivo (vía `/speckit-constitution`), indicando el
  motivo y su impacto en specs/planes existentes; las aprueban los novios (dueños del proyecto).
- **Versionado semántico:** MAJOR por eliminar o redefinir principios; MINOR por agregar
  principios o secciones o ampliarlos materialmente; PATCH por aclaraciones y redacción.
- **Cumplimiento:** cada `/speckit-plan` verifica los principios en su "Constitution Check", y
  `/speckit-analyze` se usa antes de implementar para detectar inconsistencias.
- La fuente de requerimientos de negocio es `reqs/requerimientos_fotos_boda.md`.

**Version**: 2.0.0 | **Ratified**: 2026-11-13 | **Last Amended**: 2026-11-13
