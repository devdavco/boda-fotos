# Infraestructura AWS

Inventario de la infraestructura del proyecto y pasos para recrearla. Según la constitución v2.0.0
(Principio V), la infraestructura se crea por la consola de AWS y cada recurso debe estar
documentado aquí. Un recurso que exista en AWS y no aparezca en este archivo se considera un
defecto.

- Cuenta: `248033171214`
- Región: `us-east-1` (todo, salvo lo que AWS maneja como global: IAM y CloudFront).
- Tag obligatorio en todos los recursos: `proyecto=boda`.
- Última actualización: 2026-11-18.

## Arquitectura

```
Invitado (celular) ── HTTPS ──> Amplify: front de invitados (Angular)
Novios ─────────────── HTTPS ──> Amplify: front de novios (Angular)
                                    │  rewrite /api/<*>
                                    ▼
                    CloudFront boda-cf (HTTPS, WAF básico)
                                    │  HTTP:80, solo desde CloudFront
                                    ▼
                    ALB boda-alb ── /api/* ──> EC2 Spring Boot :8080
                                                  │  rol boda-ec2-role
                                                  ├──> DynamoDB
                                                  └──> firma URLs de S3
Celular ── PUT/GET directo con URL prefirmada ──> S3 boda-fotos-2026
```

Los archivos nunca pasan por CloudFront, el ALB ni la EC2: van directo entre el navegador y S3.

## Estado actual

| Recurso | Nombre | Estado |
| --- | --- | --- |
| Bucket S3 de medios | `boda-fotos-2026` | Creado |
| Bucket S3 de artefactos | `boda-artefactos-248033171214` | Creado (§13), con el `.jar` del backend completo |
| Tabla DynamoDB | `boda-fotos` | Creada, con el índice `galeria-index` y PITR (§3) |
| Tablas DynamoDB | `boda-evento`, `boda-admins`, `boda-sesiones` | Creadas (§3) |
| Ítem del evento | `boda-2026` en `boda-evento` | Creado (§3) |
| Política IAM | `boda-backend-policy` | v3 aplicada (§4) |
| Rol IAM del backend | `boda-ec2-role` | Creado, con la política v3 |
| Security groups | `boda-alb-sg`, `boda-app-sg` | Creados; `boda-app-sg` corregido el 2026-11-14 (§5) |
| Launch template | `boda-backend-lt` | v3 por defecto (§6) |
| Target group | `boda-backend-tg` | Creado, con la instancia del ASG (§7) |
| ALB | `boda-alb` | Creado |
| CloudFront | `boda-cf` | Creado |
| Auto Scaling del backend | `boda-backend-asg` | Creado, 1/1/1 (§14) |
| SNS | `boda-alertas` | Creado |
| Alarmas | `boda-backend-sin-salud`, `boda-api-errores-5xx` | Creadas, acciones activadas y probadas el 2026-11-17 (§12) |
| Presupuesto | `boda-presupuesto` | Creado |
| App de Amplify de invitados | `boda-invitados` | Creada, rama `david/release` (§11) |
| App de Amplify de novios | `boda-novios` | Creada el 2026-11-17, rama `david/release` (§11) |

El `.jar` que corre desde el 2026-11-17 es el **backend completo** (versión final, publicada con el
procedimiento de §13).

Valores que usan las aplicaciones:

- URL del API: `https://ddl2si3h6lu7z.cloudfront.net/api/...`
- `AWS_REGION=us-east-1`
- `S3_BUCKET=boda-fotos-2026`
- `EVENT_ID=boda-2026`
- `DYNAMODB_TABLE_PHOTOS=boda-fotos`, `DYNAMODB_TABLE_EVENT=boda-evento`,
  `DYNAMODB_TABLE_ADMINS=boda-admins`, `DYNAMODB_TABLE_SESSIONS=boda-sesiones`

El backend recibe estos valores como variables de entorno desde `boda.service` (user data de §6).

## Orden para recrear desde cero

Los recursos dependen unos de otros. Si hay que recrear todo, seguir este orden:

1. Seguridad de la cuenta y presupuesto (§1)
2. Bucket S3 de medios (§2)
3. Tablas DynamoDB, índice e ítem del evento (§3)
4. Bucket de artefactos (§13)
5. Política y rol IAM (§4)
6. Security groups (§5; primero el del ALB)
7. Target group (§7)
8. ALB y su regla (§8)
9. CloudFront (§9)
10. Restringir el security group del ALB a CloudFront (§10)
11. Subir el `.jar` del backend al bucket de artefactos (§13); **antes** de la launch template y del ASG
12. Launch template (§6)
13. Auto Scaling group (§14)
14. Cuentas de los novios en `boda-admins` (§3; el hash se genera con el `.jar`)
15. App de Amplify de invitados (§11); **recrearla cambia el dominio del QR**
16. App de Amplify de novios (§11)
17. CORS del bucket (§2; necesita el dominio de Amplify de invitados)
18. SNS y alarmas, con sus acciones activadas (§12)
19. Antes del evento, limpiar las pruebas (§15)
---

## 1. Seguridad de la cuenta y presupuesto

- MFA en el usuario root. El root solo se usa para facturación o emergencias.
- Grupo IAM `admins-boda` con la política administrada `AdministratorAccess`.
- Cada persona que administra la cuenta tiene su propio usuario IAM en ese grupo, con MFA.
- No se crean access keys salvo que alguien necesite la AWS CLI en su equipo; nunca se versionan.
- Presupuesto `boda-presupuesto` (Billing → Budgets): plantilla *Monthly cost budget*, USD 30,
  aviso por correo al 85% y cuando el gasto proyectado supera el 100%.

## 2. Bucket S3 de medios: `boda-fotos-2026`

Consola: S3 → Create bucket.

- Región `us-east-1`.
- Object Ownership: *ACLs disabled*.
- Block all public access: activado.
- Versioning: desactivado.
- Cifrado por defecto: SSE-S3.

### CORS

Permissions → Cross-origin resource sharing. Solo la página de invitados sube desde el navegador a
S3 con `PUT`; la galería de los novios muestra y descarga con `<img>`, `<video>` y enlaces directos,
que no necesitan CORS, y por eso su dominio no se agrega. `http://localhost:4200` (para `ng serve`)
se quitó el 2026-11-17. Valor vigente desde esa fecha:

```json
[
  {
    "AllowedOrigins": [
      "https://david-release.d2x2ckep95pz3x.amplifyapp.com"
    ],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

`ExposeHeaders: ETag` es necesario: en las subidas multipart el navegador lee el `ETag` de cada
parte para completar la carga. Ya no están el dominio de CloudFront (ninguna página se sirve desde
ahí) ni el método `GET`. Si el front de novios llegara a descargar con `fetch`, habría que agregar
`GET` para su dominio.

### Lifecycle

Solo una regla: abortar las cargas multipart incompletas. No hay borrado automático: el material
se conserva hasta nuevo aviso de los novios (decisión del 2026-11-13).

Aplicar desde CloudShell (este comando reemplaza todas las reglas del bucket):

```bash
cat > lifecycle.json <<'EOF'
{
  "Rules": [
    {
      "ID": "abortar-multipart-incompletos",
      "Filter": {},
      "Status": "Enabled",
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
    }
  ]
}
EOF

aws s3api put-bucket-lifecycle-configuration \
  --bucket boda-fotos-2026 \
  --lifecycle-configuration file://lifecycle.json
```

## 3. Tablas DynamoDB

Todas en `us-east-1`, con el tag `proyecto=boda`, capacity mode **On-demand**, *deletion protection*
activado y cifrado por defecto (*Owned by Amazon DynamoDB*). El diseño de los atributos está en
`specs/001-wedding-media-upload/data-model.md`. El navegador nunca accede a DynamoDB: solo el rol
`boda-ec2-role` del backend.

Consola: DynamoDB → Create table → Customize settings.

| Tabla | Partition key | Otros ajustes |
| --- | --- | --- |
| `boda-fotos` | `photoId` (String), sin sort key | Índice `galeria-index`; PITR activado |
| `boda-evento` | `eventoId` (String), sin sort key | PITR activado |
| `boda-admins` | `usuario` (String), sin sort key | PITR activado |
| `boda-sesiones` | `id` (String), sin sort key | **TTL** sobre el atributo `expiraEnEpoch`; sin PITR (datos efímeros) |

Table class: DynamoDB Standard en las cuatro. En la consola, el point-in-time recovery está en la
pestaña *Backups* de cada tabla.

### Índice `galeria-index` en `boda-fotos`

DynamoDB → `boda-fotos` → Indexes → Create index.

- Partition key `galeriaEvento` (String); sort key `galeriaOrden` (String).
- Projection: *All*.
- Es un índice **disperso**: solo contiene los archivos `COMPLETADO`, porque esos dos atributos se
  quitan al eliminar un archivo. La galería de los novios sale de un `Query` sobre este índice,
  ordenado de más reciente a más antiguo, sin `Scan`.

### Ítem del evento `boda-2026` en `boda-evento`

Tiene la ventana de recepción, los topes por sesión y los contadores. Se creó desde CloudShell con
(la fecha y la hora están en hora de Cali; el backend las convierte con `America/Bogota`):

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

- Cambiar una fecha o un tope es editar este ítem en la consola; no hace falta desplegar.
- El cierre anticipado agrega `cierreAnticipadoEn` (UTC) y `cierreAnticipadoPor`. Para probar y
  restaurar la ventana, ver `specs/001-wedding-media-upload/quickstart.md` §4.9.

### Cuentas de los novios

Las cuentas `mara` y `daniel` se crearon el 2026-11-18 en `boda-admins`. Las contraseñas se
entregaron a Mara y Daniel por un canal privado y nunca se escriben en el repositorio ni aquí. La
cuenta `prueba-david` (de soporte) sigue activa durante el evento (§11).

El hash bcrypt se genera en el equipo de quien administra, con el `.jar` construido (pide la
contraseña sin eco):

```bash
java -jar backend/target/app.jar hash-contrasena
```

Crear una cuenta, desde CloudShell (repetir con `daniel` / `Daniel`):

```bash
aws dynamodb put-item --table-name boda-admins \
  --condition-expression "attribute_not_exists(usuario)" \
  --item '{"usuario":{"S":"mara"},"nombreVisible":{"S":"Mara"},"hashContrasena":{"S":"<hash>"},"intentosFallidos":{"N":"0"}}'
```

Restablecer la contraseña o desbloquear una cuenta (con un hash nuevo; quita también el bloqueo):

```bash
aws dynamodb update-item --table-name boda-admins --key '{"usuario":{"S":"mara"}}' \
  --update-expression "SET hashContrasena = :h, intentosFallidos = :z REMOVE bloqueadaHasta" \
  --expression-attribute-values '{":h":{"S":"<hash>"},":z":{"N":"0"}}'
```

## 4. IAM

### Política `boda-backend-policy` (versión v3)

IAM → Policies → `boda-backend-policy` → Edit → JSON. La v3 es la que dejó el plan técnico: limita
S3 al prefijo del evento, agrega el borrado y el aborto de multipart, el bucket de artefactos y las
tablas nuevas, y **quita `dynamodb:Scan`**.

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

Cada operación del backend y la acción que la cubre:

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

- `s3:PutObject` cubre también `CreateMultipartUpload`, `UploadPart` y `CompleteMultipartUpload`, y
  `s3:GetObject` cubre `HeadObject`.
- Las transacciones (`TransactWriteItems`) se autorizan con `dynamodb:UpdateItem` de cada tabla; el
  diseño no usa `ConditionCheck`, así que no hace falta `dynamodb:ConditionCheckItem`.
- No se usan `s3:ListBucket`, `s3:ListMultipartUploadParts`, `dynamodb:Scan` ni KMS (el cifrado es
  SSE-S3).
- La creación de cuentas y del ítem del evento se hace desde CloudShell con el usuario de consola,
  no con este rol.

### Rol `boda-ec2-role`

IAM → Roles → Create role.

- Trusted entity: AWS service → EC2.
- Políticas: `boda-backend-policy` (v3) y `AmazonSSMManagedInstanceCore` (para entrar a la instancia con
  Session Manager, sin SSH).
- Al crearlo desde la consola, AWS genera también el instance profile con el mismo nombre.

## 5. Security groups (VPC por defecto)

### `boda-alb-sg`

- Entrada: HTTP 80 solo desde el prefix list `com.amazonaws.global.cloudfront.origin-facing`.
  El ALB no es accesible directamente desde internet.
- Salida: la que viene por defecto.

Al recrear: crearlo primero con HTTP 80 desde `0.0.0.0/0`, y restringirlo al prefix list cuando
CloudFront ya funcione (paso 10).

### `boda-app-sg`
- Entrada: **solo TCP 8080**, con origen `boda-alb-sg`. No hay reglas para los puertos 80 ni 443.
- Sin SSH (puerto 22).
- Salida: la que viene por defecto. La instancia necesita salir a internet para hablar con S3,
  DynamoDB y SSM; no hay NAT Gateway porque usa subredes públicas.
- Corregido el 2026-11-14: el grupo tenía reglas de entrada para los puertos 80 y 443 por error y el
  ALB no llegaba a Spring Boot (puerto 8080). Si el target group muestra `unhealthy` con `Request
  timed out`, revisar primero esta regla.

## 6. Launch template `boda-backend-lt` (versión 3, por defecto)
- AMI: Amazon Linux 2023, 64-bit x86.
- Tipo: `t3.micro`.
- Key pair: ninguno.
- Subnet: no incluida (la define el Auto Scaling).
- Security group: `boda-app-sg`.
- IAM instance profile: `boda-ec2-role`.
- Tags de recursos: `proyecto=boda` en instancias y volúmenes.
- Versiones: la v2 solo instalaba Java. La **v3** (por defecto desde el 2026-11-14) descarga el
  `.jar` de `s3://boda-artefactos-248033171214/backend/app.jar` y lo arranca como servicio
  `systemd` (`boda.service`). Para cambiar la user data se crea una versión nueva y se marca como
  por defecto; el ASG usa siempre la versión por defecto.
- User data (v3):
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
- El arranque de una instancia nueva tarda unos 4 minutos (`dnf update`, Java y el `.jar`); por eso
  el ASG usa una gracia de health check de 600 s (§14).
- La user data usa `set -euo pipefail`: si `backend/app.jar` no existe en el bucket, el servicio no
  arranca, la instancia nunca queda sana y el ASG la reemplaza en bucle. Por eso el `.jar` se sube
  **antes** de crear el ASG (§13).
- Revisar una instancia: Session Manager → `sudo journalctl -u boda -n 100` y
  `sudo tail -n 100 /var/log/cloud-init-output.log`.
## 7. Target group `boda-backend-tg`

- Target type: Instances. Protocolo HTTP, puerto 8080. VPC por defecto.
- Health check: HTTP, ruta `/actuator/health`, **intervalo de 15 s** y **healthy threshold 2**; los
  demás valores, por defecto. Con esto una instancia nueva queda sana unos 30 s después de que
  Spring Boot responde.
- Sin targets registrados a mano: los registra el Auto Scaling (§14).

## 8. ALB `boda-alb`

- Internet-facing, IPv4, VPC por defecto, subredes de `us-east-1a` y `us-east-1b`.
- Security group: solo `boda-alb-sg`.
- Listener HTTP:80:
  - Regla `api-backend`, prioridad 10: Path `/api/*` → forward a `boda-backend-tg`.
  - Acción por defecto: respuesta fija 404, `text/plain`, cuerpo `Not found`.
- Sin la integración "CloudFront + WAF" del asistente del ALB (CloudFront se crea aparte).

## 9. CloudFront `boda-cf`

Dominio: `https://ddl2si3h6lu7z.cloudfront.net`

- Plan: Free (USD 0/mes, tarifa fija).
- Tipo: Single website configuration.
- Origen: Elastic Load Balancer → `boda-alb`.
  - Protocolo: HTTP only, puerto 80.
  - Origin IP address type: IPv4-only.
- Comportamiento por defecto:
  - Viewer protocol policy: Redirect HTTP to HTTPS.
  - Allowed methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE.
  - Cache policy: `CachingDisabled`.
  - Origin request policy: `AllViewer`.
- Seguridad: protecciones de WAF incluidas en el plan, sin monitor mode.
  - Rate limiting desactivado a propósito: en la boda muchos invitados comparten la IP pública del
    WiFi del lugar, y el WAF los bloquearía como si fueran una sola persona.

## 10. Restringir el ALB a CloudFront

En `boda-alb-sg`, reemplazar las reglas de entrada abiertas por una sola: HTTP 80 desde el prefix
list `com.amazonaws.global.cloudfront.origin-facing`.

## 11. Amplify Hosting

Dos apps desde este repositorio (`devdavco/boda-fotos`), una por front-end, con despliegue en cada
push a la rama **`david/release`**. Se trabaja en ramas `david/...` y solo lo que está listo se
mergea a `david/release`; del 19 al 22 de noviembre solo entran correcciones críticas (plan,
"Ramas y despliegue de los front-ends"). El monorepo se construye con el `amplify.yml` de la raíz.

**K7 (verificado el 2026-11-18):** cada push a `david/release` reconstruye las dos apps
(`boda-invitados` y `boda-novios`), aunque solo cambie documentación. Por eso, **del 19 al 22 de
noviembre el *auto build* de `boda-invitados` está desactivado**. Si hay que corregir algo de
invitados en esas fechas, se lanza el build a mano desde la consola de Amplify (app `boda-invitados`,
rama `david/release`) y después se vuelve a desactivar el *auto build*.

### `boda-invitados` (creada el 2026-11-14)

- Repositorio `devdavco/boda-fotos`, rama `david/release`, monorepo con la carpeta
  `front-invitados` (`AMPLIFY_MONOREPO_APP_ROOT=front-invitados`).
- Build: *Standard build*. Amplify creó el *service role* de la app.
- Tag: `proyecto=boda`.
- **Dominio: `https://david-release.d2x2ckep95pz3x.amplifyapp.com`**. Es la **URL del QR**. El
  subdominio sale del nombre de la rama y del identificador de la app, así que **no se recrea la
  app, no se renombra la rama `david/release` y no se desconecta la rama** (riesgo K6 del plan): el
  QR impreso dejaría de funcionar.
- Rewrites (Hosting → Rewrites and redirects), en este orden:

| Origen | Destino | Tipo |
| --- | --- | --- |
| `/api/<*>` | `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` | 200 (Rewrite) |
| `</^[^.]+$|\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json|webp)$)([^.]+$)/>` | `/index.html` | 200 (Rewrite) |

  La primera regla hace que el front llame a `/api/...` en su propio dominio y no necesite CORS. La
  segunda es la de SPA: las rutas de Angular resuelven a `index.html` y los archivos con extensión
  conocida (`.js`, `.css`, imágenes, etc.) se sirven tal cual.
- Verificación del *spike* de R1 (2026-11-14, con el backend mínimo):
  - `GET /api/recepcion/estado` por el dominio de Amplify: 200, el mismo JSON que por CloudFront.
  - `POST` y `DELETE` al mismo path: 405 **generado por Spring**, lo que prueba que ambos métodos
    atraviesan el rewrite y llegan al backend.
  - Rutas de Angular (rutas que no son un archivo): 200 con `index.html`.
  - Verificado el 2026-11-17: Amplify reenvía Authorization a CloudFront (login y endpoints de
    admin responden 200 por Amplify y por CloudFront).

### `boda-novios` (creada el 2026-11-17)
- Repositorio `devdavco/boda-fotos`, rama `david/release`, monorepo con la carpeta `front-novios`
  (`AMPLIFY_MONOREPO_APP_ROOT=front-novios`).
- **Dominio: `https://david-release.d26973wksxbgd3.amplifyapp.com`**. Su dominio **no** va en el
  CORS del bucket.
- Rewrites: los mismos dos de `boda-invitados`, en el mismo orden (`/api/<*>` →
  `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` como 200 y la regla de SPA hacia `/index.html`).
- El encabezado `Authorization` se reenvía por el rewrite (verificado el 2026-11-17; `research.md`
  R1).
- Primer build correcto tras el merge a `david/release` del 2026-11-17 (commit "Publicar API y
  front de novios, multipart y miniaturas").
- **Cuenta `prueba-david`** (creada el 2026-11-17 para probar el login y el resumen). Se conserva
  durante el evento por decisión de David (cuenta de soporte del responsable técnico). Las cuentas
  de los novios son `mara` y `daniel`.
## 12. Monitoreo (FR-030a)

### SNS `boda-alertas`

- Tipo Standard, display name `Boda alertas`.
- Suscripción por correo al responsable técnico (no uno de los novios), confirmada desde el correo.

### Alarmas de CloudWatch

Se crearon con las acciones desactivadas porque todavía no había backend; sin él, la primera alarma
se habría disparado de inmediato. **Las acciones se activaron el 2026-11-17** (comando al final de
esta sección). Variables comunes (CloudShell):

```bash
LB_ARN=$(aws elbv2 describe-load-balancers --names boda-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)
TG_ARN=$(aws elbv2 describe-target-groups --names boda-backend-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
SNS_ARN=$(aws sns list-topics --query "Topics[?ends_with(TopicArn, ':boda-alertas')].TopicArn" --output text)
LB_DIM=${LB_ARN#*loadbalancer/}
TG_DIM=${TG_ARN##*:}
```

`boda-backend-sin-salud`: avisa si no hay ninguna instancia sana durante 5 minutos seguidos. Usa
`HealthyHostCount` (y no `UnHealthyHostCount`) para detectar también el caso en que la instancia
desaparece.

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name boda-backend-sin-salud \
  --alarm-description "El backend no tiene instancias sanas detras del ALB durante 5 minutos" \
  --namespace AWS/ApplicationELB \
  --metric-name HealthyHostCount \
  --dimensions Name=LoadBalancer,Value=$LB_DIM Name=TargetGroup,Value=$TG_DIM \
  --statistic Minimum --period 60 \
  --evaluation-periods 5 --datapoints-to-alarm 5 \
  --threshold 1 --comparison-operator LessThanThreshold \
  --treat-missing-data breaching \
  --alarm-actions $SNS_ARN --ok-actions $SNS_ARN \
  --no-actions-enabled \
  --tags Key=proyecto,Value=boda
```

`boda-api-errores-5xx`: avisa si más del 5% de las peticiones al API terminan en error del servidor
en una ventana de 5 minutos, solo cuando hay al menos 10 peticiones (para evitar falsas alarmas
con poco tráfico). Es una aproximación: el ALB no separa métricas por ruta, así que cuenta todo el
API y no solo la autorización de subidas.

```bash
cat > alarma-5xx.json <<EOF
[
  {"Id":"t5xx","ReturnData":false,"MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"HTTPCode_Target_5XX_Count","Dimensions":[{"Name":"LoadBalancer","Value":"$LB_DIM"},{"Name":"TargetGroup","Value":"$TG_DIM"}]},"Period":300,"Stat":"Sum"}},
  {"Id":"e5xx","ReturnData":false,"MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"HTTPCode_ELB_5XX_Count","Dimensions":[{"Name":"LoadBalancer","Value":"$LB_DIM"}]},"Period":300,"Stat":"Sum"}},
  {"Id":"req","ReturnData":false,"MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"RequestCount","Dimensions":[{"Name":"LoadBalancer","Value":"$LB_DIM"},{"Name":"TargetGroup","Value":"$TG_DIM"}]},"Period":300,"Stat":"Sum"}},
  {"Id":"tasa","ReturnData":true,"Label":"Porcentaje de errores 5xx del API","Expression":"IF(FILL(req,0) >= 10, 100 * (FILL(t5xx,0) + FILL(e5xx,0)) / FILL(req,0), 0)"}
]
EOF

aws cloudwatch put-metric-alarm \
  --alarm-name boda-api-errores-5xx \
  --alarm-description "Mas del 5% de errores del servidor en el API durante 5 minutos" \
  --metrics file://alarma-5xx.json \
  --evaluation-periods 1 --datapoints-to-alarm 1 \
  --threshold 5 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions $SNS_ARN --ok-actions $SNS_ARN \
  --no-actions-enabled \
  --tags Key=proyecto,Value=boda
```

Activadas el 2026-11-17, con el backend completo desplegado y sano:

```bash
aws cloudwatch enable-alarm-actions --alarm-names boda-backend-sin-salud boda-api-errores-5xx
```

Probadas el 2026-11-17: ambas alarmas (`boda-backend-sin-salud` y `boda-api-errores-5xx`) enviaron
el correo de caída y el de recuperación, y el Auto Scaling group reemplazó la instancia en unos 7
minutos.

## 13. Bucket de artefactos y despliegue del backend

### Bucket `boda-artefactos-248033171214`

Consola: S3 → Create bucket.
- Región `us-east-1`. Tag `proyecto=boda`.
- Object Ownership: *ACLs disabled*.
- Block all public access: activado.
- Versioning: **activado** (permite volver a la versión anterior del `.jar`).
- Cifrado por defecto: SSE-S3.
- Contenido: `backend/app.jar`. La instancia lo lee con el permiso `s3:GetObject` sobre
  `backend/*` de la política v3 (§4).

### Desplegar una versión nueva del backend

No hay pipeline de CI/CD. Desde tu equipo (Java 25, Maven y el perfil de AWS CLI):

```bash
cd backend && mvn -q package
aws s3 cp target/app.jar s3://boda-artefactos-248033171214/backend/app.jar --region us-east-1
aws autoscaling start-instance-refresh --auto-scaling-group-name boda-backend-asg \
  --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200}'
```

El *instance refresh* levanta la instancia nueva antes de apagar la vieja, así que no hay corte.
Tarda unos 10 minutos (el arranque dura ~4 min y la gracia del ASG es de 600 s). Seguimiento:

```bash
aws autoscaling describe-instance-refreshes --auto-scaling-group-name boda-backend-asg
aws elbv2 describe-target-health --target-group-arn \
  $(aws elbv2 describe-target-groups --names boda-backend-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
curl -s https://ddl2si3h6lu7z.cloudfront.net/api/recepcion/estado
```

### Volver a la versión anterior

El bucket tiene versionado: restaurar una versión es copiarla encima de la actual y repetir el
*instance refresh*.

```bash
aws s3api list-object-versions --bucket boda-artefactos-248033171214 --prefix backend/app.jar \
  --query 'Versions[].[VersionId,LastModified,IsLatest]' --output table
aws s3api copy-object --bucket boda-artefactos-248033171214 --key backend/app.jar \
  --copy-source "boda-artefactos-248033171214/backend/app.jar?versionId=<VersionId anterior>"
aws autoscaling start-instance-refresh --auto-scaling-group-name boda-backend-asg \
  --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200}'
```

## 14. Auto Scaling group `boda-backend-asg`

Consola: EC2 → Auto Scaling groups → Create. **Crearlo solo cuando el `.jar` ya está en el bucket de
artefactos (§13) y la v3 de la launch template es la versión por defecto (§6).**

- Launch template: `boda-backend-lt`, versión *Default*.
- VPC por defecto, subredes de `us-east-1a` y `us-east-1b`.
- Load balancing: asociado al target group existente `boda-backend-tg` (§7).
- Health checks: tipo **ELB**, con **gracia de 600 s**. El arranque tarda unos 4 minutos
  (`dnf update` y descarga del `.jar`); con 300 s el ASG daba la instancia por muerta antes de que
  terminara de arrancar.
- Capacidad: mínima 1, deseada 1, máxima 1. Sin políticas de escalado.
- Instance maintenance policy: *launch before terminating*, mínimo 100 % y máximo 200 % de
  instancias sanas.
- Tags, ambos propagados a las instancias: `proyecto=boda` y `Name=boda-backend`.
- Comprobación: el target group `boda-backend-tg` tiene 1 destino *healthy* y
  `https://ddl2si3h6lu7z.cloudfront.net/api/recepcion/estado` responde 200.
- Después del evento (2026-12-02): eliminar el ASG y el ALB para apagar el cómputo (constitución,
  Principio V).

## 15. Limpiar las pruebas antes del evento
Script: `infra/scripts/limpiar-pruebas.sh`. Borra las pruebas hechas en producción
(`quickstart.md` §4 y §4.9, tarea T102). Corre en **AWS CloudShell**, con el usuario de consola
(administrador), no con el rol `boda-ec2-role`: la política v3 no tiene `dynamodb:Scan` ni
`s3:ListBucket`, y el script necesita ambos. Solo usa bash, AWS CLI v2 y `jq`, que ya vienen en
CloudShell.

Recibe un prefijo del nombre del invitado (por ejemplo `Prueba`). Busca en `boda-sesiones` las
sesiones de subida del evento cuyo último nombre empieza así y borra todo lo que cuelga de ellas:

1. Las cargas multipart pendientes de esos archivos.
2. Los objetos de S3 de esos archivos, original y miniatura, bajo `eventos/boda-2026/`. La
   miniatura se borra aunque el ítem no la registre.
3. Los ítems de esos archivos en `boda-fotos`, en cualquier estado.
4. Recalcula `archivosTotal`, `fotos`, `videos` y `ultimaSubidaEn` del ítem `boda-2026` en
   `boda-evento` a partir de los archivos `COMPLETADO` que quedan. No resta a mano. Si no queda
   ninguno, los contadores quedan en 0 y `ultimaSubidaEn` se quita.
5. Los ítems de esas sesiones en `boda-sesiones`. Es el último paso a propósito: las sesiones son
   el vínculo para encontrar sus archivos, así que si algo falla a la mitad se puede repetir el
   script con el mismo prefijo.

### Bajar el script a CloudShell
El repositorio es privado, así que CloudShell no puede descargarlo con un enlace público. Dos
opciones:

- **Subir el archivo** (la más simple): con el repositorio en la rama `david/001-impl` en tu
  equipo, en CloudShell usar *Actions → Upload file* y elegir `infra/scripts/limpiar-pruebas.sh`.
  Queda en `~/limpiar-pruebas.sh`. Después: `chmod +x limpiar-pruebas.sh`.
- **Clonar el repositorio** con un token de GitHub de solo lectura (*fine-grained*, permiso
  *Contents: Read-only* sobre `devdavco/boda-fotos`). No guardar el token en ningún archivo:
  ```bash
  git clone --branch david/001-impl --depth 1 https://github.com/devdavco/boda-fotos.git
  # usuario: devdavco; contraseña: el token
  cd boda-fotos && chmod +x infra/scripts/limpiar-pruebas.sh
  ```
  Para traer cambios posteriores: `git pull` dentro de `boda-fotos`.

### Uso
```bash
./limpiar-pruebas.sh Prueba              # --dry-run por defecto: solo lista, no borra
./limpiar-pruebas.sh --ejecutar Prueba   # borra de verdad
./limpiar-pruebas.sh --help
```
- El prefijo distingue mayúsculas de minúsculas y no puede ser vacío. Si tiene espacios, va entre
  comillas. `Prueba` encuentra `Prueba T052`.
- Variables opcionales: `EVENT_ID` (por defecto `boda-2026`) y `S3_BUCKET` (por defecto
  `boda-fotos-2026`). Con `EVENT_ID=boda-prueba` limpia el evento de prueba de T045, si su ítem
  existe en `boda-evento`.
- Siempre trabaja en `us-east-1`, aunque CloudShell esté abierto en otra región.
- Códigos de salida: `0` bien; `1` error de AWS o inconsistencia; `2` uso incorrecto; `3` no hay
  ninguna sesión que coincida con el prefijo (no hace nada en silencio).

Procedimiento recomendado:
1. Correr sin `--ejecutar` y revisar la lista de sesiones, archivos, objetos y los contadores que
   quedarían. Los nombres que imprime deben ser todos de prueba.
2. Correr con `--ejecutar`.
3. Leer el resumen: sesiones, ítems y objetos borrados, multipart abortados y los contadores
   finales. Si todo lo que había era de prueba, deben quedar en 0.

Avisos del script que conviene leer:
- Un archivo con un nombre que empieza por el prefijo pero cuya sesión **no** coincide (el
  invitado cambió el nombre después) se avisa y **no** se borra.
- Un archivo de una sesión que sí coincide, pero con otro nombre, **sí** se borra (se borra todo
  lo de la sesión) y se avisa.

Advertencias:
- El borrado es definitivo. El bucket no tiene versionado. Las tablas `boda-fotos` y `boda-evento`
  tienen PITR, que restaura a una tabla nueva, no sobre la existente.
- Usarlo antes del 2026-11-19. Con la recepción abierta y público real, un prefijo demasiado
  corto borraría fotos de invitados: por eso el modo por defecto solo simula.
- Los contadores se escriben con una condición sobre el valor de `archivosTotal` que se leyó justo
  antes. Si alguien sube o elimina un archivo mientras se recalcula, el script falla con un
  mensaje; los archivos ya borrados no se restauran y basta repetir el comando.
- No toca otros prefijos de S3. Para vaciar `eventos/boda-prueba/` (T102) usar
  `aws s3 rm s3://boda-fotos-2026/eventos/boda-prueba/ --recursive`, con `--dryrun` primero.

## Pendientes

- Generar el QR con el dominio definitivo de `boda-invitados` (T105).
- Después del cierre del 22: revisar los archivos `PENDIENTE` de modo `SIMPLE` cuyo objeto exista
  en S3 (T106).
- Reactivar el *auto build* de `boda-invitados` después del 22.
- El 2026-12-02 apagar el cómputo (Auto Scaling y ALB) según la constitución (T107).
