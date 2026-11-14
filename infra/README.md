# Infraestructura AWS

Inventario de la infraestructura del proyecto y pasos para recrearla. Según la constitución v2.0.0
(Principio V), la infraestructura se crea por la consola de AWS y cada recurso debe estar
documentado aquí. Un recurso que exista en AWS y no aparezca en este archivo se considera un
defecto.

- Cuenta: `248033171214`
- Región: `us-east-1` (todo, salvo lo que AWS maneja como global: IAM y CloudFront).
- Tag obligatorio en todos los recursos: `proyecto=boda`.
- Última actualización: 2026-11-13.

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
| Tabla DynamoDB | `boda-fotos` | Creada (diseño final según el plan) |
| Rol IAM del backend | `boda-ec2-role` | Creado (se ampliará según el plan) |
| Security groups | `boda-alb-sg`, `boda-app-sg` | Creados |
| Launch template | `boda-backend-lt` (v2) | Creada |
| Target group | `boda-backend-tg` | Creado, sin instancias |
| ALB | `boda-alb` | Creado |
| CloudFront | `boda-cf` | Creado |
| SNS | `boda-alertas` | Creado |
| Alarmas | `boda-backend-sin-salud`, `boda-api-errores-5xx` | Creadas, acciones desactivadas |
| Presupuesto | `boda-presupuesto` | Creado |
| Auto Scaling del backend | — | Pendiente (falta el .jar) |
| Apps de Amplify | — | Pendiente (faltan los front-ends) |

Valores que usan las aplicaciones:

- URL del API: `https://ddl2si3h6lu7z.cloudfront.net/api/...`
- `AWS_REGION=us-east-1`
- `S3_BUCKET=boda-fotos-2026`

## Orden para recrear desde cero

Los recursos dependen unos de otros. Si hay que recrear todo, seguir este orden:

1. Seguridad de la cuenta y presupuesto
2. Bucket S3
3. Tabla DynamoDB
4. Política y rol IAM
5. Security groups (primero el del ALB)
6. Launch template
7. Target group
8. ALB y su regla
9. CloudFront
10. Restringir el security group del ALB a CloudFront
11. CORS del bucket (necesita los dominios de CloudFront y Amplify)
12. SNS y alarmas

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

Permissions → Cross-origin resource sharing. Debe incluir el dominio de Amplify de invitados
cuando exista. `http://localhost:4200` se quita antes del 2026-11-19.

```json
[
  {
    "AllowedOrigins": [
      "https://ddl2si3h6lu7z.cloudfront.net",
      "http://localhost:4200"
    ],
    "AllowedMethods": ["PUT", "GET"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

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

## 3. Tabla DynamoDB: `boda-fotos`

Consola: DynamoDB → Create table → Customize settings.

- Partition key: `photoId` (String). Sin sort key.
- Table class: DynamoDB Standard.
- Capacity mode: On-demand.
- Encryption: *Owned by Amazon DynamoDB*.
- Deletion protection: activado.
- Point-in-time recovery: activar antes del 2026-11-19.

El diseño definitivo (administradores, configuración del evento, sesiones de subida, metadatos)
sale del plan técnico. Cada tabla nueva se agrega a esta sección.

## 4. IAM

### Política `boda-backend-policy`

IAM → Policies → Create policy → JSON. Se ampliará según el plan (por ejemplo `s3:DeleteObject`,
`s3:AbortMultipartUpload`, más tablas y parámetros de SSM).

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "S3Fotos",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::boda-fotos-2026/*"
    },
    {
      "Sid": "DynamoFotos",
      "Effect": "Allow",
      "Action": [
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:UpdateItem",
        "dynamodb:Query",
        "dynamodb:Scan"
      ],
      "Resource": [
        "arn:aws:dynamodb:us-east-1:248033171214:table/boda-fotos",
        "arn:aws:dynamodb:us-east-1:248033171214:table/boda-fotos/index/*"
      ]
    }
  ]
}
```

### Rol `boda-ec2-role`

IAM → Roles → Create role.

- Trusted entity: AWS service → EC2.
- Políticas: `boda-backend-policy` y `AmazonSSMManagedInstanceCore` (para entrar a la instancia con
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

- Entrada: HTTP 80 y TCP 8080, ambos con origen `boda-alb-sg`.
- Sin SSH (puerto 22).
- Salida: la que viene por defecto. La instancia necesita salir a internet para hablar con S3,
  DynamoDB y SSM; no hay NAT Gateway porque usa subredes públicas.

## 6. Launch template `boda-backend-lt` (versión 2, por defecto)

- AMI: Amazon Linux 2023, 64-bit x86.
- Tipo: `t3.micro`.
- Key pair: ninguno.
- Subnet: no incluida (la define el Auto Scaling).
- Security group: `boda-app-sg`.
- IAM instance profile: `boda-ec2-role`.
- Tags de recursos: `proyecto=boda` en instancias y volúmenes.
- User data:

```bash
#!/bin/bash
dnf update -y
dnf install -y java-25-amazon-corretto-headless
```

Pendiente: agregar al user data la descarga y el arranque del .jar, cuando se defina cómo llega a
la instancia.

## 7. Target group `boda-backend-tg`

- Target type: Instances. Protocolo HTTP, puerto 8080. VPC por defecto.
- Health check: HTTP, ruta `/actuator/health`.
- Sin targets registrados a mano: los registra el Auto Scaling.

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

## 11. Amplify (pendiente)

Dos apps desde este repositorio, una por front-end, con despliegue en cada push. En cada una:

- Rewrite `/api/<*>` → `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` (tipo 200, reverse proxy),
  para que el front llame a `/api` en su propio dominio y no necesite CORS.
- Rewrite de SPA para que las rutas de Angular resuelvan a `index.html`.

Al crearlas, agregar aquí sus dominios y añadir el de invitados al CORS del bucket.

## 12. Monitoreo (FR-030a)

### SNS `boda-alertas`

- Tipo Standard, display name `Boda alertas`.
- Suscripción por correo al responsable técnico (no uno de los novios), confirmada desde el correo.

### Alarmas de CloudWatch

Se crearon con las acciones desactivadas porque todavía no hay backend; sin él, la primera alarma
se dispararía de inmediato. Variables comunes (CloudShell):

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

Activar ambas cuando el backend esté desplegado y sano:

```bash
aws cloudwatch enable-alarm-actions --alarm-names boda-backend-sin-salud boda-api-errores-5xx
```

## Pendientes

- Auto Scaling del backend (1 instancia) con `boda-backend-lt` y `boda-backend-tg`, y definir cómo
  llega el .jar a la instancia.
- Activar las acciones de las alarmas.
- Apps de Amplify y su dominio en el CORS del bucket.
- Ampliar la política IAM y crear las tablas de DynamoDB según el plan.
- Antes del 2026-11-19: quitar `localhost` del CORS, activar point-in-time recovery y ejecutar la
  lista de chequeo manual en celulares reales.
- Después del evento: apagar o eliminar el cómputo (Auto Scaling, ALB) según la constitución.
