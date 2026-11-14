# Quickstart y verificación: Fotos y videos de la boda vía QR

**Feature**: `001-wedding-media-upload` | **Fecha**: 2026-11-14 | **Plan**: [plan.md](plan.md)

Cómo correr el sistema y cómo comprobar que cumple la spec. La **sección 4 es la lista de chequeo
manual** que exige la constitución. Se ejecuta completa en producción antes del 2026-11-19
(SC-008). Contratos: [api.md](contracts/api.md), [ui.md](contracts/ui.md). Datos:
[data-model.md](data-model.md).

## 1. Requisitos

- Java 25 (Corretto 25) y Maven 3.9+. Node 22 y Angular CLI 21.
- Perfil de AWS CLI en la máquina local con acceso a `us-east-1` (solo para desarrollo local).
- Recursos AWS de la sección "Recursos AWS nuevos o modificados" del [plan](plan.md), creados y
  documentados en [`infra/README.md`](../../infra/README.md).
- Un iPhone con Safari y un Android con Chrome (y, si es posible, Samsung Internet) con datos
  móviles, no solo wifi.

## 2. Desarrollo local

Para no tocar el evento real, el backend local usa un evento de prueba `boda-prueba` en las mismas
tablas y el mismo bucket. Crear su ítem en `boda-evento` (igual que el de `boda-2026` del plan, con
la ventana abierta hoy).

```bash
# Backend
cd backend
EVENT_ID=boda-prueba AWS_REGION=us-east-1 S3_BUCKET=boda-fotos-2026 \
DYNAMODB_TABLE_PHOTOS=boda-fotos DYNAMODB_TABLE_EVENT=boda-evento \
DYNAMODB_TABLE_ADMINS=boda-admins DYNAMODB_TABLE_SESSIONS=boda-sesiones \
AWS_PROFILE=<tu-perfil> mvn spring-boot:run
curl -s localhost:8080/actuator/health          # {"status":"UP"}
curl -s localhost:8080/api/recepcion/estado     # {"estado":"ABIERTA",...}

# Front de invitados (otra terminal): http://localhost:4200
cd front-invitados && npm ci && npx ng serve --proxy-config proxy.conf.json

# Front de novios (otra terminal): http://localhost:4201
cd front-novios && npm ci && npx ng serve --port 4201 --proxy-config proxy.conf.json
```

- El CORS del bucket permite `http://localhost:4200`. El front de novios no necesita CORS: solo
  usa `<img>`, `<video>` y enlaces de descarga.
- Al terminar las pruebas locales, borrar el prefijo `eventos/boda-prueba/` del bucket y los ítems
  de `boda-prueba`.

## 3. Despliegue

**Backend** (procedimiento de research R10; queda documentado en `infra/README.md`):

```bash
cd backend && mvn -q package
aws s3 cp target/app.jar s3://boda-artefactos-248033171214/backend/app.jar
aws autoscaling start-instance-refresh --auto-scaling-group-name boda-backend-asg \
  --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200}'
```

Resultado esperado: en menos de 10 minutos el target group `boda-backend-tg` tiene 1 instancia
*healthy* y `curl -s https://ddl2si3h6lu7z.cloudfront.net/api/recepcion/estado` responde JSON.

**Front-ends**: merge a `david/release` y `git push` de esa rama; el trabajo diario va en ramas
`david/...` y solo se mergea lo que está listo. Amplify construye y publica las dos apps desde
`david/release`. Dominio de invitados: `https://david-release.d2x2ckep95pz3x.amplifyapp.com`. Comprobar en cada dominio de Amplify que `/api/recepcion/estado` responde el mismo
JSON (esto valida el rewrite).

**Primera vez** (orden del plan, "Recursos AWS"): crear la rama `david/release` con `amplify.yml` y
el esqueleto de `front-invitados` antes de conectar Amplify, y subir el `.jar` mínimo al bucket de
artefactos antes de crear el ASG.

## 4. Lista de chequeo manual en producción (constitución)

Hacerla el **2026-11-17 o el 2026-11-18**. Como la recepción real abre el 19, durante la prueba
se abre la ventana a mano y después se restaura.

**4.0 Preparación**

- [ ] En `boda-evento` → ítem `boda-2026`, anotar los valores actuales y cambiar `aperturaLocal`
      a una hora ya pasada de hoy y `cierreLocal` a dentro de 3 horas.
- [ ] El front de invitados en el iPhone muestra el formulario de subida.

**4.1 Subida desde celulares reales con red móvil** (US1, SC-001 a SC-003)

- [ ] iPhone/Safari, datos móviles: escanear el QR impreso, escribir el nombre, subir 5 fotos
      (incluida una HEIC si el celular las genera) y 1 video < 100 MB. Cada archivo muestra
      progreso y "Listo". La primera foto sube en menos de 1 minuto desde el escaneo.
- [ ] Android/Chrome, datos móviles: lo mismo, más **un video de 100 MB o más** (multipart).
- [ ] Samsung Internet (si hay un equipo disponible): una foto y un video.
- [ ] Recargar la página: el nombre aparece prellenado (FR-004a).
- [ ] Dos celulares suben un archivo con el mismo nombre original: los dos aparecen en la galería
      (US1-7).

**4.2 Reintento** (US1-4, US1-5, FR-007, FR-008)

- [ ] Empezar a subir 4 archivos y activar el modo avión a mitad. Al volver la señal, "Reintentar
      fallidos" reenvía solo los que no terminaron; los terminados no se repiten.
- [ ] Video ≥ 100 MB: cortar la red a mitad y reintentar. Continúa desde las partes que faltaban
      (el progreso no vuelve a 0).
- [ ] Video ≥ 100 MB: activar el modo avión **más de 5 minutos** a mitad de la subida y volver a
      conectar. La subida sigue: las partes pendientes piden URLs nuevas en vez de fallar por URLs
      vencidas.

**4.3 Rechazos** (US1-6, FR-010 a FR-012a)

- [ ] Un PDF o un `.gif` → "Este tipo de archivo no se puede subir…", y los demás siguen.
- [ ] Una foto de más de 40 MB y un video de más de 500 MB → mensaje de tamaño.
- [ ] Llamada directa al API saltándose la página, con un tamaño mayor al máximo:
      `curl -s -X POST https://ddl2si3h6lu7z.cloudfront.net/api/subidas -H 'Content-Type: application/json' -H 'X-Sesion-Subida: <id>' -d '{"nombreInvitado":"Prueba","nombreArchivo":"x.mp4","tamano":600000000}'`
      → `400 ARCHIVO_MUY_GRANDE`.
- [ ] Tope de sesión: poner temporalmente `maxArchivosPorSesion = 3` en el ítem del evento, subir
      4 archivos → el cuarto muestra el mensaje del límite. Restaurar `300`.

**4.4 Ventana de recepción** (US2, US4-2, SC-005)

- [ ] **Antes de la apertura**: poner `aperturaLocal` en el futuro → la página dice que la
      recepción aún no abre, con la fecha, y el `curl` de 4.3 responde `403 RECEPCION_NO_INICIADA`.
- [ ] **Después del cierre**: poner `cierreLocal` en el pasado → la página dice "ya cerró" y el
      `curl` responde `403 RECEPCION_CERRADA`.
- [ ] **Multipart después del cierre**: empezar un video ≥ 100 MB, cambiar `cierreLocal` al pasado
      antes de que termine → la finalización se rechaza y el video no aparece en la galería
      (US2-3).
- [ ] **Cierre anticipado**: restaurar la ventana abierta y, desde el front de novios, tocar
      "Cerrar la recepción ahora" → la página de invitados muestra "cerrada". Reiniciar la
      instancia (instance refresh) y comprobar que sigue cerrada (US4-2). Luego quitar
      `cierreAnticipadoEn` y `cierreAnticipadoPor` del ítem desde la consola.

**4.5 Login de los novios** (US3-1 a US3-3, FR-024, FR-025)

- [ ] Login correcto de `mara` y de `daniel`.
- [ ] Usuario existente con contraseña mala, y usuario inexistente: el **mismo** mensaje genérico.
- [ ] 5 fallos seguidos de `daniel` → el sexto intento, aun con la contraseña buena, da el
      mensaje genérico. Después, quitar `bloqueadaHasta` desde la consola (o esperar 15 min).
- [ ] En `boda-admins` se ven solo hashes `$2a$12$…`, nunca contraseñas.

**4.6 Galería, descarga y eliminación** (US3-4 a US3-6, US4-1, SC-007, FR-030b)

- [ ] La galería muestra los archivos de 4.1, del más reciente al más antiguo, con nombre y fecha;
      el contador coincide.
- [ ] Videos y fotos JPG con miniatura; HEIC con ícono. Bajar hasta cargar otro bloque: aparece
      en menos de 3 s.
- [ ] Abrir una foto y un video en tamaño completo; descargar el original de cada uno con su
      nombre original.
- [ ] Eliminar un archivo → desaparece y el contador baja.

**4.7 Privacidad** (FR-020, SC-006)

- [ ] Abrir en una ventana privada una URL directa sin firma,
      `https://boda-fotos-2026.s3.us-east-1.amazonaws.com/eventos/boda-2026/originales/<id>.jpg`
      → `AccessDenied`.
- [ ] Una URL prefirmada vieja (de más de 15 minutos) → `AccessDenied` / `Request has expired`.
- [ ] `curl -s https://ddl2si3h6lu7z.cloudfront.net/api/admin/galeria` sin token → `401`.
- [ ] El ALB no responde directo desde internet: `curl -m 5 http://<DNS-del-ALB>/api/recepcion/estado`
      → timeout.

**4.8 Alertas** (FR-030a, SC-010)

- [ ] Con las acciones de las alarmas activadas, detener el servicio por Session Manager
      (`sudo systemctl stop boda`) → llega el correo de `boda-backend-sin-salud` en menos de
      10 min. `sudo systemctl start boda` → llega el correo de vuelta a OK.

**4.9 Restaurar para el evento** (obligatorio)

- [ ] Ítem `boda-2026` con los valores reales: `aperturaLocal = 2026-11-19T00:00`,
      `cierreLocal = 2026-11-22T12:00`, `disponibleHastaLocal = 2026-12-01T23:59:59`, topes
      `300` / `5368709120`, sin `cierreAnticipado*`.
- [ ] Eliminar desde la galería **todos los archivos de prueba**; el contador queda en 0.
- [ ] La página de invitados muestra "abre el jueves 19 de noviembre".
- [ ] Quitar `http://localhost:4200` del CORS del bucket; activar PITR en las tablas.
- [ ] Imprimir o validar el QR con la URL definitiva de Amplify (`https://david-release.d2x2ckep95pz3x.amplifyapp.com`).

## 5. Durante y después del evento

- **Congelamiento** del 2026-11-19 al 2026-11-22: no hacer merge a `david/release` salvo
  correcciones que impidan subir o consultar (Amplify despliega en cada push a esa rama).
- **Front de novios durante el congelamiento** (excepción aprobada; ver plan, "Complexity
  Tracking"): si no quedó publicado el 18, desactivar el *auto build* de `david/release` en `boda-invitados`,
  mergear `front-novios` a `david/release` antes del 2026-11-22 a las 12:00 y repetir §4.5 y §4.6 desde la interfaz.
  Mientras tanto, el cierre anticipado se hace a mano en el ítem `boda-2026` (`cierreAnticipadoEn`
  en UTC y `cierreAnticipadoPor`).
- **Huérfanos** (después del cierre del 22): buscar en `boda-fotos` los ítems `PENDIENTE` de modo
  `SIMPLE` cuyo objeto exista en S3. Son archivos que el invitado subió pero cuya finalización no
  llegó (por ejemplo, cerró el navegador justo al terminar). Mostrárselos a los novios y, si los
  quieren, completarlos a mano o descargarlos para ellos.
- **2026-12-02**: apagar el cómputo según la constitución: borrar `boda-backend-asg` y `boda-alb`
  (y sus IPv4). CloudFront (plan Free), las tablas, Amplify y el bucket se pueden quedar. El
  material se conserva hasta que los novios pidan borrarlo (FR-033).
- **Eliminación definitiva, cuando los novios la pidan**: borrar el prefijo `eventos/boda-2026/`
  del bucket y los ítems del evento, y registrarlo en `infra/README.md`.
