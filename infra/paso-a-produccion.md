# Paso a producción del trabajo de `daniel/001-admin`

Guía para llevar a producción lo que está en la rama `daniel/001-admin`: API de administración,
front de novios, ventana de recepción en el servidor, multipart y miniaturas. Cada paso dice qué
hacer, cómo comprobar que salió bien y cómo volver atrás.

Cubre las tareas **T075 → T074 → T076 → PR a `david/release` → T022 → T083/T094**, en ese orden.
El inventario de recursos está en [`README.md`](README.md); esta guía no lo repite.

| Dato | Valor |
| --- | --- |
| Cuenta / región | `248033171214` / `us-east-1` |
| Backend (ASG / target group) | `boda-backend-asg` / `boda-backend-tg` |
| Bucket del `.jar` | `s3://boda-artefactos-248033171214/backend/app.jar` (con versionado) |
| CloudFront (API) | `https://ddl2si3h6lu7z.cloudfront.net` |
| Front de invitados (URL del QR) | `https://david-release.d2x2ckep95pz3x.amplifyapp.com` |
| Evento real | `boda-2026` en la tabla `boda-evento` |

## Reglas mientras dure

- **Las pruebas escriben en el evento real.** El nombre de invitado de prueba empieza por
  `Prueba` y todo se limpia al final con `infra/scripts/limpiar-pruebas.sh`.
- **La ventana de recepción se abre solo para probar y se cierra al terminar** (paso 3.4). Si se
  olvida, la recepción queda abierta antes del 19.
- No recrear ni renombrar la app de Amplify `boda-invitados` ni la rama `david/release`: cambiaría
  la URL del QR.
- Hacerlo **antes del 18 de noviembre**. Del 19 al 22 solo entran correcciones críticas.
- Avisar a David antes de empezar: el paso 1 reemplaza el backend que él publicó.

---

## Paso 0. Preparar (en tu PC)

1. Tener la rama al día y compilar con las pruebas:

   ```powershell
   cd C:\Proyectos\boda-local\boda-fotos
   git switch daniel/001-admin; git pull
   $env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-25.0.5"
   $mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter mvn.cmd | Select-Object -First 1).FullName
   & $mvn -q -f backend\pom.xml package      # corre las 40 pruebas y genera backend\target\app.jar
   ```

2. Generar **dos** hashes, uno por cuenta, cada uno con su propia contraseña (mínimo 10
   caracteres). El comando necesita una terminal interactiva, así que se escribe directamente en
   PowerShell, no en un script:

   ```powershell
   & "$env:USERPROFILE\.jdks\corretto-25.0.5\bin\java.exe" -jar backend\target\app.jar hash-contrasena
   ```

   Guardar cada hash (`$2a$12$...`) para el paso 2. Las contraseñas se entregan a Mara y a Daniel
   por un canal privado y **nunca** se escriben en el repositorio ni en un chat.

3. Decidir desde dónde se sube el `.jar`:
   - **Opción A, desde tu PC**, si tienes AWS CLI con credenciales de administrador:
     `aws sts get-caller-identity` debe mostrar la cuenta `248033171214`.
   - **Opción B, desde CloudShell**: subir `backend\target\app.jar` con **Actions → Upload file**
     (pesa ~47 MB) y ejecutar ahí los comandos del paso 1.

---

## Paso 1. Publicar el backend completo (T075)

Antes de reemplazar, anotar la versión actual para poder volver atrás:

```bash
aws s3api list-object-versions --bucket boda-artefactos-248033171214 --prefix backend/app.jar \
  --query 'Versions[?IsLatest].[VersionId,LastModified]' --output text
```

Subir y lanzar el *instance refresh* (levanta la instancia nueva antes de apagar la vieja, sin
corte; tarda unos 10 minutos):

```bash
aws s3 cp app.jar s3://boda-artefactos-248033171214/backend/app.jar --region us-east-1
#        ^ en tu PC: backend/target/app.jar
aws autoscaling start-instance-refresh --auto-scaling-group-name boda-backend-asg \
  --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200}'
```

**Comprobar:**

```bash
aws autoscaling describe-instance-refreshes --auto-scaling-group-name boda-backend-asg \
  --query 'InstanceRefreshes[0].[Status,PercentageComplete]' --output text     # Successful 100
curl -s https://ddl2si3h6lu7z.cloudfront.net/api/recepcion/estado              # "estado":"NO_INICIADA"
curl -s -o /dev/null -w '%{http_code}\n' https://ddl2si3h6lu7z.cloudfront.net/api/admin/resumen
#   → 401 (antes también era 401; el login del paso 3 es lo que confirma que el API nuevo está arriba)
```

**Volver atrás:** copiar la versión anotada encima de la actual y repetir el *instance refresh*
(`README.md` §13, "Volver a la versión anterior").

---

## Paso 2. Crear las cuentas de Mara y Daniel (T074)

En CloudShell, con los hashes del paso 0. Las comillas simples evitan que bash interprete los `$`
del hash:

```bash
aws dynamodb put-item --table-name boda-admins \
  --condition-expression "attribute_not_exists(usuario)" \
  --item '{"usuario":{"S":"mara"},"nombreVisible":{"S":"Mara"},"hashContrasena":{"S":"<hash de mara>"},"intentosFallidos":{"N":"0"}}'

aws dynamodb put-item --table-name boda-admins \
  --condition-expression "attribute_not_exists(usuario)" \
  --item '{"usuario":{"S":"daniel"},"nombreVisible":{"S":"Daniel"},"hashContrasena":{"S":"<hash de daniel>"},"intentosFallidos":{"N":"0"}}'
```

**Comprobar:** `aws dynamodb scan --table-name boda-admins --query 'Items[].[usuario.S,hashContrasena.S]' --output text`
muestra solo `$2a$12$...`, nunca una contraseña.

**Restablecer una contraseña o desbloquear** (también sirve si se equivoca un hash):

```bash
aws dynamodb update-item --table-name boda-admins --key '{"usuario":{"S":"mara"}}' \
  --update-expression "SET hashContrasena = :h, intentosFallidos = :z REMOVE bloqueadaHasta" \
  --expression-attribute-values '{":h":{"S":"<hash nuevo>"},":z":{"N":"0"}}'
```

---

## Paso 3. Probar el API contra producción (T076)

Desde CloudShell o desde Git Bash. `API` es CloudFront y `AMP` es el dominio de Amplify.

```bash
API=https://ddl2si3h6lu7z.cloudfront.net/api
AMP=https://david-release.d2x2ckep95pz3x.amplifyapp.com/api
login() { curl -s -X POST -H 'Content-Type: application/json' -d "{\"usuario\":\"$1\",\"contrasena\":\"$2\"}" "$API/admin/login"; }
```

### 3.1 Login y mensajes (quickstart §4.5)

```bash
login mara '<contraseña de mara>'          # 200 con "token", "nombreVisible":"Mara"
login daniel '<contraseña de daniel>'      # 200
login mara 'mala'                          # 401 CREDENCIALES_INVALIDAS
login pepe 'mala'                          # 401, el MISMO mensaje
```

Bloqueo (opcional, con `daniel`): 5 logins con contraseña mala y luego uno con la buena → el
sexto también da 401. Desbloquear con el comando del paso 2.

### 3.2 El encabezado `Authorization` pasa por Amplify (riesgo abierto)

Nunca se ha probado. Si Amplify no reenvía `Authorization`, todo el front de novios responde 401.

```bash
TOKEN=$(login mara '<contraseña de mara>' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -s -w ' [%{http_code}]\n' -H "Authorization: Bearer $TOKEN" "$API/admin/resumen"   # 200 por CloudFront
curl -s -w ' [%{http_code}]\n' -H "Authorization: Bearer $TOKEN" "$AMP/admin/resumen"   # 200 por Amplify
```

- **Ambos 200:** seguir.
- **CloudFront 200 y Amplify 401:** Amplify descarta el encabezado. **No publicar el front de
  novios todavía** y avisar: hace falta el plan B de research R1 (el front llama directo a
  CloudFront, con CORS en Spring para el dominio de `boda-novios`).

### 3.3 Galería, URLs y privacidad (quickstart §4.5 y §4.7)

```bash
A="Authorization: Bearer $TOKEN"
curl -s -H "$A" "$API/admin/galeria?limite=2"                       # 200, "elementos":[...]
curl -s -w ' [%{http_code}]\n' "$API/admin/galeria"                 # 401 sin token
curl -s -w ' [%{http_code}]\n' -H "$A" "$API/admin/galeria?limite=99"   # 400 PETICION_INVALIDA
curl -s -X POST -w ' [%{http_code}]\n' -H "$A" "$API/admin/logout"  # 204
curl -s -w ' [%{http_code}]\n' -H "$A" "$API/admin/resumen"         # 401: el token ya no sirve
```

Con archivos de prueba (paso 3.4): `GET $API/admin/archivos/<archivoId>/url?uso=ver` y
`?uso=descargar` → 200 con una URL; abrirla en el navegador muestra o descarga el original con su
nombre.

### 3.4 Subida real con la ventana abierta

1. Abrir la ventana solo para probar:

   ```bash
   aws dynamodb update-item --table-name boda-evento --key '{"eventoId":{"S":"boda-2026"}}' \
     --update-expression "SET aperturaLocal = :a" --expression-attribute-values '{":a":{"S":"2026-11-14T00:00"}}'
   curl -s $API/recepcion/estado        # ABIERTA
   ```

2. Mientras el front de invitados en producción sea el de `david/release`, el celular sube por la
   página actual (sin multipart ni miniaturas). Esas dos cosas se prueban después del merge
   (paso 6). Nombre: `Prueba Daniel`.
3. Ver los archivos en el API (`$API/admin/galeria`) y probar `url?uso=ver|descargar` y
   `DELETE $API/admin/archivos/<id>` → 204, y que el contador de `$API/admin/resumen` baja.
4. **Cerrar la ventana al terminar:**

   ```bash
   aws dynamodb update-item --table-name boda-evento --key '{"eventoId":{"S":"boda-2026"}}' \
     --update-expression "SET aperturaLocal = :a" --expression-attribute-values '{":a":{"S":"2026-11-19T00:00"}}'
   curl -s $API/recepcion/estado        # NO_INICIADA
   ```

> El **cierre anticipado** (`POST $API/admin/recepcion/cerrar`) solo actúa con la recepción
> abierta. Si se prueba en el punto 3.4, deshacerlo antes de cerrar la ventana:
> `--update-expression "REMOVE cierreAnticipadoEn, cierreAnticipadoPor"`.

---

## Paso 4. Rama `daniel/release` y PR hacia `david/release`

Solo si el paso 3.2 dio 200 por Amplify.

```powershell
cd C:\Proyectos\boda-local\boda-fotos
git switch daniel/001-admin; git pull
git switch -c daniel/release
git push -u origin daniel/release
```

Abrir el PR en GitHub: **base `david/release` ← compare `daniel/release`**, o con
`gh pr create --base david/release --head daniel/release`.

En la descripción del PR conviene decir:

- Trae también **3 commits de David** que están en `david/001-impl` y aún no en `david/release`
  (T059 y el script `limpiar-pruebas.sh`). El merge entra sin conflictos (comprobado con
  `git merge-tree`).
- Archivos de David que cambian: `SecurityConfig`, `ArchivoRepository`, `VentanaRecepcion`,
  `SubidaService`, `AwsConfig` (solo agrega `S3_ENDPOINT`, vacío en AWS), `EventoRepository`,
  `cola-subida.ts`, `app.ts`, `app.config.ts`.
- Decisiones nuevas en `research.md`: R4 (la miniatura sube antes del original), R5, R6 y R7.
- Al mergear, Amplify publica `front-invitados` (multipart, miniaturas, aviso de recepción
  cerrada, sin `#spike`). `front-novios` todavía no se publica: falta la app (paso 5).

**Volver atrás:** si algo sale mal después del merge, revertir el commit de merge en
`david/release` (`git revert -m 1 <merge>`) y Amplify vuelve a publicar la versión anterior.

---

## Paso 5. Crear la app de Amplify `boda-novios` (T022)

Después de que el PR esté mergeado, para que su primer build encuentre `front-novios/`.

Consola → Amplify → **Create new app** → GitHub, igual que `boda-invitados` (`README.md` §11):

- Repositorio `devdavco/boda-fotos`, rama **`david/release`**.
- **My app is a monorepo**, carpeta `front-novios`. Amplify pone
  `AMPLIFY_MONOREPO_APP_ROOT=front-novios` y usa el `amplify.yml` de la raíz.
- Nombre `boda-novios`, tag `proyecto=boda`, *service role* nuevo.
- **Rewrites and redirects**, en este orden:

  | Origen | Destino | Tipo |
  | --- | --- | --- |
  | `/api/<*>` | `https://ddl2si3h6lu7z.cloudfront.net/api/<*>` | 200 (Rewrite) |
  | `</^[^.]+$\|\.(?!(css\|gif\|ico\|jpg\|js\|png\|txt\|svg\|woff\|woff2\|ttf\|map\|json\|webp)$)([^.]+$)/>` | `/index.html` | 200 (Rewrite) |

  (En la consola, la regla de SPA va sin las barras invertidas que necesita esta tabla.)

**Comprobar** (`<novios>` es el dominio que asigna Amplify, `https://david-release.<id>.amplifyapp.com`):

- `curl -s https://<novios>/api/recepcion/estado` → mismo JSON que por CloudFront.
- `https://<novios>/entrar` y `https://<novios>/archivo/x` cargan la app (prueba la regla de SPA).
- Entrar como Mara desde el celular y el PC (quickstart §4.5 y §4.6).
- Anotar el dominio en `README.md` §11. **No** va en el CORS del bucket.
- K7 (de T022): hacer un push que toque solo `front-invitados/` y ver si `boda-novios` también se
  reconstruye. Si no, sobra desactivar el *auto build* de `boda-invitados` del 19 al 22.

**Volver atrás:** borrar la app `boda-novios`. No afecta a `boda-invitados` ni al QR.

---

## Paso 6. Pruebas con celulares reales (después del merge)

Con la ventana abierta como en 3.4 y cerrada al terminar:

- iPhone (Safari) y Android (Chrome), por 4G: fotos, una HEIC real y un video de 300 MB
  (multipart). Modo avión a mitad de la subida, más de 5 minutos, y ver que sigue sola (T057,
  quickstart §4.2). Comprobar las miniaturas en `eventos/boda-2026/miniaturas/`.
- Galería de novios desde el celular: miniaturas, vista completa, descarga y eliminación.
- Cerrar la recepción desde la galería y ver la página de invitados en "ya cerró"; luego quitar
  `cierreAnticipado*`.

## Paso 7. Dejar todo listo para el evento

Lo pide la constitución (T102, quickstart §4.9) y lo hace quien termine las pruebas:

```bash
./limpiar-pruebas.sh Prueba             # revisar la lista
./limpiar-pruebas.sh --ejecutar Prueba  # borrar; el resumen debe quedar en 0 / 0 / 0
```

- Ventana real: `aperturaLocal = 2026-11-19T00:00`, `cierreLocal = 2026-11-22T12:00`,
  `disponibleHastaLocal = 2026-12-01T23:59:59`, sin `cierreAnticipado*`.
- Quitar `http://localhost:4200` del CORS del bucket.
- Activar las acciones de las alarmas (T093).
- Registrar en `README.md` lo hecho: cuentas creadas (sin contraseñas), dominio de `boda-novios`
  y fecha del despliegue (T104).
