# Ejecutar todo localmente (sin AWS)

Guía para levantar en esta máquina el backend, el front de invitados y el front de novios, con
**DynamoDB Local** y un **S3 falso** (Adobe S3Mock) en Docker. Nada de esto toca la cuenta de AWS
ni el evento real `boda-2026`: todo usa el evento de prueba `boda-prueba` y vive en memoria.

```text
Navegador ── http://localhost:4200  front-invitados (ng serve) ─┐
          ── http://localhost:4201  front-novios    (ng serve) ─┤ proxy /api
                                                                ▼
                                         http://localhost:8080  backend (Spring Boot)
                                              │                     │
                              DynamoDB Local :8000        S3 falso :9090 (bucket boda-fotos-local)
Navegador ── PUT / GET con URL prefirmada ─────────────────────────▲
```

Igual que en producción, los archivos van **directo del navegador al S3** con URLs prefirmadas;
no pasan por el backend.

---

## 1. Requisitos (una sola vez)

Ya están instalados en esta máquina; esto es para comprobarlo.

| Qué | Para qué | Cómo comprobarlo |
| --- | --- | --- |
| Docker Desktop | DynamoDB Local y S3 falso | Abrirlo y esperar a que diga *Engine running*. `docker info` |
| JDK 25 (Corretto) | Backend | Está en `%USERPROFILE%\.jdks\corretto-25.0.5` (lo instaló IntelliJ) |
| Maven | Compilar el backend | Lo encuentra el script en `%USERPROFILE%\.m2\wrapper\dists` |
| Node.js | Los dos front-ends | `node -v` |
| AWS CLI | Crear tablas en DynamoDB Local | `aws --version` |
| PowerShell | Los scripts de `local/` | Funcionan en Windows PowerShell 5.1 (la terminal por defecto) y en PowerShell 7 |

Las dependencias de los front-ends se instalan una vez (o cuando cambie `package-lock.json`):

```powershell
cd C:\Proyectos\boda-local\boda-fotos\front-invitados; npm ci
cd C:\Proyectos\boda-local\boda-fotos\front-novios;    npm ci
```

> Todos los comandos de esta guía son de **PowerShell** y se escriben desde la raíz del repo,
> `C:\Proyectos\boda-local\boda-fotos`, salvo donde se indica otra carpeta.

---

## 2. Levantar DynamoDB Local y el S3 falso

Con Docker Desktop abierto:

```powershell
cd C:\Proyectos\boda-local\boda-fotos
.\local\preparar.ps1
```

El script:

1. Arranca los contenedores de `local/docker-compose.yml`: DynamoDB Local en el puerto **8000** y
   S3Mock en el **9090**, con el bucket `boda-fotos-local` ya creado.
2. Crea las tablas `boda-fotos` (con el índice `galeria-index`), `boda-evento`, `boda-admins` y
   `boda-sesiones`, iguales a las de `specs/001-wedding-media-upload/data-model.md`.
3. Crea las cuentas de los novios **`mara`** y **`daniel`**, ambas con la contraseña de prueba
   **`clave-de-prueba-1`**. Esa contraseña solo existe en este entorno local.
4. Crea el evento `boda-prueba` con la **recepción abierta**: apertura ayer, cierre en 7 días y
   periodo de descarga de 14 días.

Si algo queda raro, se puede volver a ejecutar: borra todo y empieza de cero.

---

## 3. Backend (terminal 1)

```powershell
cd C:\Proyectos\boda-local\boda-fotos
.\local\backend.ps1
```

Compila el `.jar` (unos 30 s la primera vez) y lo ejecuta en primer plano con:

- `EVENT_ID=boda-prueba` y `S3_BUCKET=boda-fotos-local`.
- `AWS_ENDPOINT_URL_DYNAMODB=http://localhost:8000`, para que el SDK use DynamoDB Local.
- `S3_ENDPOINT=http://localhost:9090`, para que las URLs prefirmadas apunten al S3 falso. Esta
  variable solo existe en local; en la EC2 no se define y el backend usa S3 real.
- Credenciales falsas (`local` / `local`). Ningún perfil de AWS del equipo se usa.

Está listo cuando aparece `Started BodaApplication`. Se detiene con **Ctrl+C**. Para arrancarlo de
nuevo sin recompilar: `.\local\backend.ps1 -SinCompilar`.

Comprobación (en otra terminal):

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health        # status: UP
Invoke-RestMethod http://localhost:8080/api/recepcion/estado   # estado: ABIERTA
```

---

## 4. Front de invitados (terminal 2)

```powershell
cd C:\Proyectos\boda-local\boda-fotos\front-invitados
npm start
```

Abrir **http://localhost:4200**. `ng serve` reenvía `/api` al backend con `proxy.conf.json`.

Qué probar:

1. Escribir un nombre (por costumbre, que empiece por `Prueba`).
2. **Elegir fotos y videos**: JPG, PNG, WEBP o videos MP4/MOV de **menos de 100 MB**.
3. Cada archivo muestra su barra de progreso y termina en **Listo ✓**.
4. Probar también un archivo que no sea foto ni video (por ejemplo un `.pdf`) para ver el rechazo.

Para verlo como en un celular: **F12 → icono de dispositivo (Ctrl+Shift+M)** y elegir un iPhone o
un Pixel.

---

## 5. Front de novios (terminal 3)

```powershell
cd C:\Proyectos\boda-local\boda-fotos\front-novios
npx ng serve --port 4201
```

Abrir **http://localhost:4201** y entrar con `mara` / `clave-de-prueba-1` (o `daniel`).

Qué probar:

1. **Galería**: el contador ("2 archivos (2 fotos, 0 videos) · Última subida: …"), el estado de la
   recepción y el banner con la fecha límite. Las fotos aparecen en la cuadrícula.
2. **Vista completa**: tocar una foto o un video.
3. **Descargar original**: se descarga con el nombre que tenía el archivo en el equipo.
4. **Eliminar**: pide confirmación, el archivo sale de la galería y el contador baja.
5. **Cerrar la recepción ahora**: pide confirmación; recargar **http://localhost:4200** y la página
   de invitados dice que la recepción ya cerró. Para abrirla otra vez: `.\local\evento.ps1 abierta`.
6. **Contraseña mala**: siempre el mismo mensaje. Al **5.º fallo seguido** la cuenta se bloquea
   15 minutos (incluso con la contraseña correcta). Para desbloquear rápido, volver a ejecutar
   `.\local\preparar.ps1` (borra todo) o esperar los 15 minutos.
7. **Cerrar sesión**: vuelve a `/entrar`.

---

## 6. Ver lo que hay en el S3 falso y en DynamoDB

En una terminal de PowerShell, primero cargar las variables locales (credenciales falsas y
endpoints) para que la CLI de AWS no use tu cuenta real:

```powershell
cd C:\Proyectos\boda-local\boda-fotos
. .\local\comun.ps1

# Objetos subidos (originales y, cuando exista T055, miniaturas)
aws s3 ls s3://boda-fotos-local --recursive --endpoint-url http://localhost:9090

# Archivos registrados y su estado (PENDIENTE, COMPLETADO, ELIMINADO...)
aws dynamodb scan --table-name boda-fotos --endpoint-url http://localhost:8000 `
  --query "Items[].[nombreInvitado.S, nombreOriginal.S, estado.S, tamano.N]" --output table

# Evento: ventana, contadores y cierre anticipado
.\local\evento.ps1 ver
```

> Ese `scan` es solo para mirar en local. El backend nunca hace `Scan`.

---

## 7. Probar los estados de la recepción

```powershell
.\local\evento.ps1 abierta             # los invitados pueden subir
.\local\evento.ps1 no-iniciada         # "La recepción de fotos y videos abre el jueves 19 de noviembre de 2026."
.\local\evento.ps1 cerrada             # "La recepción de fotos y videos ya cerró. ¡Gracias por compartir!"
.\local\evento.ps1 periodo-terminado   # la galería de novios muestra el mensaje de fin del periodo
```

Cada cambio surte efecto en la siguiente petición: basta con recargar la página. El backend lee el
evento sin caché, igual que en producción. `abierta` también quita un cierre anticipado hecho desde
la galería.

---

## 8. Apagar todo

1. **Ctrl+C** en las tres terminales (backend y los dos `ng serve`).
2. Detener los contenedores (borra los datos, que viven en memoria):

   ```powershell
   docker compose -f .\local\docker-compose.yml down
   ```

Al día siguiente se empieza otra vez desde el paso 2.

---

## 9. Probar desde el celular (mismo WiFi)

El celular entra a las páginas que sirve el PC por la IP del PC en el WiFi, y sube los archivos
directo al S3 falso del PC. Todo sigue siendo local: nada sale a internet.

**Una sola vez: abrir el firewall de Windows.** En una terminal **como administrador**:

```powershell
New-NetFirewallRule -DisplayName 'boda-local (celular)' -Direction Inbound -Protocol TCP `
  -LocalPort 4200,4201,9090 -RemoteAddress LocalSubnet -Action Allow -Profile Any
```

La regla solo acepta equipos de la misma red local (`LocalSubnet`). Para quitarla cuando ya no se
use: `Remove-NetFirewallRule -DisplayName 'boda-local (celular)'` (también como administrador).

**Cada vez**, igual que los pasos 2 a 5 pero con estos cambios:

```powershell
.\local\preparar.ps1                                   # si los contenedores no están arriba
.\local\backend.ps1 -Celular                           # terminal 1: firma las URLs con la IP del PC
cd front-invitados; npx ng serve --host 0.0.0.0 --port 4200   # terminal 2
cd front-novios;    npx ng serve --host 0.0.0.0 --port 4201   # terminal 3
```

`backend.ps1 -Celular` detecta la IP del PC (la de la interfaz con salida al router) y muestra las
direcciones para el celular, por ejemplo:

```text
  Invitados: http://192.168.81.44:4200
  Novios:    http://192.168.81.44:4201
```

Si detecta otra IP (por ejemplo, la de una VPN o un adaptador virtual), pasarla a mano:
`.\local\backend.ps1 -Celular -IpLan 192.168.x.y` (la del WiFi sale en `ipconfig`).

Notas:

- Las páginas también siguen funcionando en el PC, por `localhost` o por la IP.
- Sin `-Celular`, el celular abre las páginas pero **no puede subir ni ver fotos**: las URLs
  firmadas apuntarían a `localhost:9090`, que en el celular es el propio celular.
- Si el celular no abre la página: comprobar que está en el **mismo WiFi** (no en datos móviles)
  y que la regla del firewall existe. Algunos routers de invitados aíslan a los equipos entre sí;
  en ese caso no hay forma de conectarlos.
- Desde un iPhone, las fotos HEIC llegan convertidas a JPEG (research R13), igual que en
  producción.

---

## 10. Qué no se puede probar en local

| Qué | Por qué | Dónde se prueba |
| --- | --- | --- |
| Que S3 rechace un archivo de otro tamaño (riesgo K9) | S3Mock no valida las firmas de las URLs | Producción, `quickstart.md` §4 |
| Videos de 100 MB o más | Falta el multipart en el front (T054) | Cuando esté T054 |
| Miniaturas | Falta generarlas en el celular (T055, T056); la galería carga el original | Cuando estén T055 y T056 |
| Red móvil (4G) y celulares fuera de la casa | El celular entra por el WiFi al PC (sección 9) | Producción, desde los celulares |
| Rewrite de Amplify, CloudFront, ALB y alarmas | Son infraestructura de AWS | Producción |

Algunos textos están fijos según la spec y no cambian con las fechas de prueba: el banner dice
siempre "hasta el martes 1 de diciembre de 2026" y el aviso de recepción no iniciada dice "abre el
jueves 19 de noviembre".

---

## 11. Problemas comunes

| Síntoma | Solución |
| --- | --- |
| "no se puede cargar el archivo ... la ejecución de scripts está deshabilitada" | Una vez, en esa terminal: `Set-ExecutionPolicy -Scope Process Bypass`. |
| `preparar.ps1`: "¿Docker Desktop está abierto?" | Abrir Docker Desktop y esperar a *Engine running*. |
| `port is already allocated` (8000 o 9090) | Otro contenedor usa el puerto: `docker ps` y `docker stop <id>`. |
| `/api/recepcion/estado` responde `ERROR_INTERNO` | Las tablas o el evento no existen en DynamoDB Local (por ejemplo, `preparar.ps1` no terminó o se reinició Docker). Ejecutar `.\local\preparar.ps1` y revisar que termine en "Listo."; no hace falta reiniciar el backend. |
| Las páginas muestran error y el log del backend dice `No existe el evento 'boda-prueba'` | Faltó el paso 2, o se reinició Docker: ejecutar `.\local\preparar.ps1` (no hace falta reiniciar el backend). |
| `Port 4200 is already in use` | Ya hay otro `ng serve` abierto: cerrarlo o usar `--port 4202`. |
| Las páginas dicen "No se pudo consultar el estado" | El backend no está corriendo (paso 3). |
| Las fotos no se ven en la galería | El S3 falso se reinició y perdió los objetos: subir de nuevo. |
| `aws` usa la cuenta real | Faltó `. .\local\comun.ps1` en esa terminal. Los scripts de `local/` lo cargan solos. |
