#!/usr/bin/env bash
#
# limpiar-pruebas.sh: borra las pruebas hechas en producción antes del evento.
#
# Para correr en AWS CloudShell (bash, AWS CLI v2 y jq; nada más). Procedimiento y contexto:
# infra/README.md ("Limpiar las pruebas") y specs/001-wedding-media-upload/quickstart.md §4.9.
#
# Dado un prefijo del nombre del invitado, busca las sesiones de subida (boda-sesiones, tipo
# SUBIDA, atributo ultimoNombre) cuyo nombre empieza así y borra todo lo que cuelga de ellas:
#   1. Las cargas multipart pendientes de esos archivos.
#   2. Los objetos de S3 de esos archivos (original y miniatura) bajo eventos/<EVENT_ID>/.
#   3. Los ítems de esos archivos en boda-fotos (sea cual sea su estado).
#   4. Recalcula archivosTotal, fotos, videos y ultimaSubidaEn del ítem del evento en
#      boda-evento a partir de los archivos COMPLETADO que quedan (no resta a mano).
#   5. Los ítems de esas sesiones en boda-sesiones (al final, para poder repetir el script si
#      algo falla a la mitad: las sesiones son el vínculo para encontrar sus archivos).
#
# Por defecto solo simula (--dry-run): lista lo que borraría y no modifica nada. Para borrar de
# verdad hay que pasar --ejecutar. El borrado es definitivo: el bucket no tiene versionado.
#
# Códigos de salida: 0 bien; 1 error de AWS o inconsistencia; 2 uso incorrecto;
#                    3 no hay nada que coincida con el prefijo.

set -euo pipefail

# --- Configuración (mismos nombres que las variables de la aplicación; ver infra/README.md) ---
EVENT_ID="${EVENT_ID:-boda-2026}"
S3_BUCKET="${S3_BUCKET:-boda-fotos-2026}"
TABLA_FOTOS="${DYNAMODB_TABLE_PHOTOS:-boda-fotos}"
TABLA_EVENTO="${DYNAMODB_TABLE_EVENT:-boda-evento}"
TABLA_SESIONES="${DYNAMODB_TABLE_SESSIONS:-boda-sesiones}"

# Todo el proyecto está en us-east-1; CloudShell puede estar abierto en otra región.
export AWS_DEFAULT_REGION=us-east-1
export AWS_REGION=us-east-1
export AWS_PAGER=""
export AWS_DEFAULT_OUTPUT=json

# --- Utilidades ---
uso() {
  cat <<'EOF'
Uso: limpiar-pruebas.sh [--dry-run | --ejecutar] <prefijo-del-nombre>

  <prefijo-del-nombre>  Las sesiones de subida cuyo nombre de invitado empiece así se borran,
                        con todos sus archivos. Distingue mayúsculas de minúsculas.
                        Ejemplo: "Prueba" encuentra "Prueba T052".
  --dry-run             (por defecto) solo lista lo que borraría.
  --ejecutar            borra de verdad.
  -h, --help            muestra esta ayuda.

Variables opcionales: EVENT_ID (boda-2026), S3_BUCKET (boda-fotos-2026).
Códigos de salida: 0 bien, 1 error, 2 uso incorrecto, 3 nada coincide con el prefijo.
EOF
}

die() {
  local codigo=$1
  shift
  printf 'ERROR: %s\n' "$*" >&2
  exit "$codigo"
}

aviso() {
  printf 'AVISO: %s\n' "$*" >&2
}

trap 'rc=$?; printf "ERROR: falló un comando en la línea %s (código %s). Revisa el mensaje anterior.\n" "$LINENO" "$rc" >&2; exit 1' ERR

# --- Argumentos ---
MODO=""
PREFIJO=""
TIENE_PREFIJO=0
SOLO_POSICIONALES=0
while [ $# -gt 0 ]; do
  if [ "$SOLO_POSICIONALES" -eq 0 ]; then
    case "$1" in
      --dry-run | --ejecutar)
        nuevo=${1#--}
        if [ -n "$MODO" ] && [ "$MODO" != "$nuevo" ]; then
          uso >&2
          die 2 "--dry-run y --ejecutar son contradictorios."
        fi
        MODO=$nuevo
        shift
        continue
        ;;
      -h | --help)
        uso
        exit 0
        ;;
      --)
        SOLO_POSICIONALES=1
        shift
        continue
        ;;
      -*)
        uso >&2
        die 2 "Opción desconocida: $1"
        ;;
    esac
  fi
  if [ "$TIENE_PREFIJO" -eq 1 ]; then
    uso >&2
    die 2 "Se recibió más de un prefijo. Si tiene espacios, ponlo entre comillas."
  fi
  PREFIJO=$1
  TIENE_PREFIJO=1
  shift
done
MODO=${MODO:-dry-run}

if [ "$TIENE_PREFIJO" -eq 0 ] || [ -z "${PREFIJO//[[:space:]]/}" ]; then
  uso >&2
  die 2 "Falta el prefijo del nombre del invitado (un prefijo vacío coincidiría con todos)."
fi
if [ "${#PREFIJO}" -gt 60 ]; then
  die 2 "El prefijo tiene más de 60 caracteres; los nombres de invitado no pasan de 60."
fi
if ! [[ $EVENT_ID =~ ^[A-Za-z0-9][A-Za-z0-9-]*$ ]]; then
  die 2 "EVENT_ID inválido: '$EVENT_ID'."
fi

for comando in aws jq; do
  command -v "$comando" >/dev/null 2>&1 || die 1 "No se encontró '$comando' en el PATH."
done

PREFIJO_S3="eventos/${EVENT_ID}/"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

VALOR_EVENTO=$(jq -nc --arg e "$EVENT_ID" '{":evento":{"S":$e}}')
CLAVE_EVENTO=$(jq -nc --arg e "$EVENT_ID" '{"eventoId":{"S":$e}}')

# --- Lectura (solo lectura; consistente) ---

# Ítems de boda-fotos del evento, aplanados, como un arreglo JSON.
leer_archivos() {
  aws dynamodb scan --table-name "$TABLA_FOTOS" --consistent-read \
    --filter-expression '#e = :evento' \
    --expression-attribute-names '{"#e":"eventoId"}' \
    --expression-attribute-values "$VALOR_EVENTO" |
    jq -nc '[inputs | .Items[]? | {
      photoId: .photoId.S,
      sesionId: .sesionId.S,
      nombre: .nombreInvitado.S,
      estado: .estado.S,
      tipo: .tipo.S,
      modo: .modo.S,
      claveS3: .claveS3.S,
      claveMiniatura: .claveMiniatura.S,
      subidoEn: .subidoEn.S
    }]'
}

# Sesiones de subida del evento cuyo último nombre empieza por el prefijo.
leer_sesiones() {
  local valores
  valores=$(jq -nc --arg e "$EVENT_ID" --arg p "$PREFIJO" \
    '{":tipo":{"S":"SUBIDA"},":evento":{"S":$e},":prefijo":{"S":$p}}')
  aws dynamodb scan --table-name "$TABLA_SESIONES" --consistent-read \
    --filter-expression '#t = :tipo AND #e = :evento AND begins_with(#n, :prefijo)' \
    --expression-attribute-names '{"#t":"tipo","#e":"eventoId","#n":"ultimoNombre"}' \
    --expression-attribute-values "$valores" |
    jq -nc '[inputs | .Items[]? | {
      id: .id.S,
      token: (.id.S | ltrimstr("sub#")),
      nombre: .ultimoNombre.S,
      archivos: ((.archivos.N // "0") | tonumber),
      creadaEn: .creadaEn.S
    }]'
}

# Contadores actuales del ítem del evento; "null" si el ítem no existe.
leer_evento() {
  aws dynamodb get-item --table-name "$TABLA_EVENTO" --key "$CLAVE_EVENTO" --consistent-read |
    jq -c 'if (.Item // null) == null then null else .Item | {
      archivosTotal: (.archivosTotal.N | if . == null then null else tonumber end),
      fotos: (.fotos.N | if . == null then null else tonumber end),
      videos: (.videos.N | if . == null then null else tonumber end),
      ultimaSubidaEn: (.ultimaSubidaEn.S // null)
    } end'
}

# Objetos de S3 bajo eventos/<EVENT_ID>/ como arreglo [{key,size}].
listar_objetos() {
  aws s3api list-objects-v2 --bucket "$S3_BUCKET" --prefix "$PREFIJO_S3" |
    jq -nc '[inputs | (.Contents // [])[] | {key: .Key, size: .Size}]'
}

# Cargas multipart sin terminar bajo eventos/<EVENT_ID>/ como arreglo [{key,uploadId,iniciado}].
listar_multiparts() {
  aws s3api list-multipart-uploads --bucket "$S3_BUCKET" --prefix "$PREFIJO_S3" |
    jq -nc '[inputs | (.Uploads // [])[] | {key: .Key, uploadId: .UploadId, iniciado: .Initiated}]'
}

# Recibe un arreglo de archivos (stdin) y devuelve los contadores que le corresponden.
calcular_contadores() {
  jq -c '[.[] | select(.estado == "COMPLETADO")] as $c | {
    archivosTotal: ($c | length),
    fotos: ($c | map(select(.tipo == "FOTO")) | length),
    videos: ($c | map(select(.tipo == "VIDEO")) | length),
    ultimaSubidaEn: ($c | map(.subidoEn) | max)
  }'
}

formatear_contadores() {
  jq -r '"archivosTotal=\(.archivosTotal // "(sin valor)")  fotos=\(.fotos // "(sin valor)")  videos=\(.videos // "(sin valor)")  ultimaSubidaEn=\(.ultimaSubidaEn // "(sin valor)")"' <<<"$1"
}

# --- Escritura ---

# Borra, en lotes de 25 con reintento de los no procesados, los ítems de una tabla.
#   $1 tabla, $2 nombre de la clave, $3 archivo con un arreglo JSON de valores de la clave.
borrar_items() {
  local tabla=$1 atributo=$2 archivo=$3 lote pendientes respuesta intentos
  while IFS= read -r lote; do
    pendientes=$(jq -c --arg t "$tabla" --arg a "$atributo" \
      '{($t): map({DeleteRequest: {Key: {($a): {S: .}}}})}' <<<"$lote")
    intentos=0
    while :; do
      respuesta=$(aws dynamodb batch-write-item --request-items "$pendientes" </dev/null)
      pendientes=$(jq -c '.UnprocessedItems // {}' <<<"$respuesta")
      [ "$pendientes" = "{}" ] && break
      intentos=$((intentos + 1))
      [ "$intentos" -ge 8 ] && die 1 "DynamoDB no procesó todos los borrados de $tabla tras 8 intentos."
      sleep "$intentos"
    done
  done < <(jq -c '[range(0; length; 25) as $i | .[$i:$i+25]][]' "$archivo")
}

# Borra objetos de S3 en lotes de 1000. $1 = archivo con un arreglo JSON de claves.
borrar_objetos() {
  local archivo=$1 lote respuesta errores
  while IFS= read -r lote; do
    jq -c '{Objects: map({Key: .}), Quiet: true}' <<<"$lote" >"$TMP/lote-s3.json"
    respuesta=$(aws s3api delete-objects --bucket "$S3_BUCKET" --delete "file://$TMP/lote-s3.json" </dev/null)
    errores=$(jq -r '(.Errors // []) | length' <<<"$respuesta")
    if [ "$errores" != "0" ]; then
      jq -r '.Errors[] | "  \(.Key): \(.Code) \(.Message)"' <<<"$respuesta" >&2
      die 1 "S3 no pudo borrar $errores objeto(s)."
    fi
  done < <(jq -c '[range(0; length; 1000) as $i | .[$i:$i+1000]][]' "$archivo")
}

# Escribe los contadores nuevos solo si archivosTotal sigue como se leyó (control de concurrencia).
#   $1 contadores nuevos (JSON), $2 contadores leídos justo antes (JSON).
actualizar_contadores() {
  local nuevos=$1 previos=$2 valores expresion condicion salida
  valores=$(jq -nc --argjson n "$nuevos" --argjson p "$previos" '
    {":t": {N: ($n.archivosTotal | tostring)},
     ":f": {N: ($n.fotos | tostring)},
     ":v": {N: ($n.videos | tostring)}}
    + (if $n.ultimaSubidaEn != null then {":u": {S: $n.ultimaSubidaEn}} else {} end)
    + (if $p.archivosTotal != null then {":previo": {N: ($p.archivosTotal | tostring)}} else {} end)')
  expresion='SET archivosTotal = :t, fotos = :f, videos = :v'
  if [ "$(jq -r '.ultimaSubidaEn // "" | length' <<<"$nuevos")" -gt 0 ]; then
    expresion="$expresion, ultimaSubidaEn = :u"
  else
    expresion="$expresion REMOVE ultimaSubidaEn"
  fi
  if [ "$(jq -r '.archivosTotal | type' <<<"$previos")" = "null" ]; then
    condicion='attribute_exists(eventoId) AND attribute_not_exists(archivosTotal)'
  else
    condicion='attribute_exists(eventoId) AND archivosTotal = :previo'
  fi
  if ! salida=$(aws dynamodb update-item --table-name "$TABLA_EVENTO" --key "$CLAVE_EVENTO" \
    --update-expression "$expresion" --condition-expression "$condicion" \
    --expression-attribute-values "$valores" 2>&1); then
    if [[ $salida == *ConditionalCheckFailedException* ]]; then
      die 1 "Los contadores de '$EVENT_ID' cambiaron mientras se recalculaban (alguien subió o eliminó un archivo). Los archivos ya se borraron; las sesiones siguen en $TABLA_SESIONES. Vuelve a correr el script con el mismo prefijo."
    fi
    printf '%s\n' "$salida" >&2
    die 1 "No se pudieron actualizar los contadores."
  fi
}

# =========================================================================================
# Plan: qué coincide
# =========================================================================================

CUENTA=$(aws sts get-caller-identity --query Account --output text)
if [ "$MODO" = "ejecutar" ]; then
  printf '== limpiar-pruebas: MODO EJECUTAR (borra de verdad) ==\n'
else
  printf '== limpiar-pruebas: MODO DRY-RUN (no se modifica nada) ==\n'
fi
printf 'Cuenta %s, región %s, evento %s, bucket %s\n' "$CUENTA" "$AWS_DEFAULT_REGION" "$EVENT_ID" "$S3_BUCKET"
printf 'Prefijo del nombre: "%s"\n\n' "$PREFIJO"

EVENTO_ANTES=$(leer_evento)
[ "$EVENTO_ANTES" = "null" ] && die 1 "No existe el ítem '$EVENT_ID' en la tabla $TABLA_EVENTO."

leer_sesiones >"$TMP/sesiones.json"
leer_archivos >"$TMP/archivos.json"

# Archivos de las sesiones encontradas. sesionId en boda-fotos es el token sin "sub#".
jq -c --slurpfile s "$TMP/sesiones.json" '
  ($s[0] | map({key: .token, value: true}) | from_entries) as $set
  | map(select($set[.sesionId // ""]))' "$TMP/archivos.json" >"$TMP/archivos-borrar.json"

# Archivos cuyo nombre empieza por el prefijo pero cuya sesión no coincide: no se tocan.
jq -c --arg p "$PREFIJO" --slurpfile s "$TMP/sesiones.json" '
  ($s[0] | map({key: .token, value: true}) | from_entries) as $set
  | map(select((((.nombre // "") | startswith($p))) and ($set[.sesionId // ""] | not)))' \
  "$TMP/archivos.json" >"$TMP/archivos-sueltos.json"

# Archivos de las sesiones encontradas con un nombre que no empieza por el prefijo.
jq -c --arg p "$PREFIJO" \
  'map(select(((.nombre // "") | startswith($p)) | not))' \
  "$TMP/archivos-borrar.json" >"$TMP/archivos-otro-nombre.json"

N_SESIONES=$(jq 'length' "$TMP/sesiones.json")
N_ARCHIVOS=$(jq 'length' "$TMP/archivos-borrar.json")
N_SUELTOS=$(jq 'length' "$TMP/archivos-sueltos.json")
N_OTRO_NOMBRE=$(jq 'length' "$TMP/archivos-otro-nombre.json")

if [ "$N_SUELTOS" -gt 0 ]; then
  aviso "Archivos con nombre que empieza por \"$PREFIJO\" pero cuya sesión no coincide (por ejemplo, el invitado cambió el nombre después): $N_SUELTOS. NO se borran:"
  jq -r '.[] | "  \(.estado)  \(.photoId)  \"\(.nombre)\""' "$TMP/archivos-sueltos.json" >&2
fi

if [ "$N_SESIONES" -eq 0 ]; then
  die 3 "No hay sesiones de subida del evento '$EVENT_ID' cuyo nombre empiece por \"$PREFIJO\". No se borró nada."
fi

# Claves de S3 de esos archivos: original y miniatura (guardadas o derivadas del photoId, porque
# la miniatura puede existir en S3 sin figurar en el ítem). Solo claves bajo eventos/<EVENT_ID>/.
jq -c --arg pre "$PREFIJO_S3" '
  [.[] | .claveS3, .claveMiniatura, ($pre + "miniaturas/" + .photoId + ".jpg")]
  | map(select(. != null)) | unique' "$TMP/archivos-borrar.json" >"$TMP/claves-todas.json"
jq -c --arg pre "$PREFIJO_S3" 'map(select(startswith($pre)))' "$TMP/claves-todas.json" >"$TMP/claves.json"
N_FUERA=$(($(jq 'length' "$TMP/claves-todas.json") - $(jq 'length' "$TMP/claves.json")))
if [ "$N_FUERA" -gt 0 ]; then
  aviso "$N_FUERA clave(s) de S3 de esos ítems están fuera de $PREFIJO_S3 y se ignoran."
fi

listar_objetos >"$TMP/objetos-todos.json"
jq -c --slurpfile c "$TMP/claves.json" '
  ($c[0] | map({key: ., value: true}) | from_entries) as $set | map(select($set[.key]))' \
  "$TMP/objetos-todos.json" >"$TMP/objetos-borrar.json"

listar_multiparts >"$TMP/multiparts-todos.json"
jq -c --slurpfile c "$TMP/claves.json" '
  ($c[0] | map({key: ., value: true}) | from_entries) as $set | map(select($set[.key]))' \
  "$TMP/multiparts-todos.json" >"$TMP/multiparts-borrar.json"

N_OBJETOS=$(jq 'length' "$TMP/objetos-borrar.json")
N_ORIGINALES=$(jq --arg pre "${PREFIJO_S3}originales/" '[.[] | select(.key | startswith($pre))] | length' "$TMP/objetos-borrar.json")
N_MINIATURAS=$(jq --arg pre "${PREFIJO_S3}miniaturas/" '[.[] | select(.key | startswith($pre))] | length' "$TMP/objetos-borrar.json")
N_MULTIPARTS=$(jq 'length' "$TMP/multiparts-borrar.json")

# Contadores que quedarían: la misma función que se usa después de borrar, sobre los archivos
# que no se borran.
jq -c --slurpfile b "$TMP/archivos-borrar.json" '
  ($b[0] | map({key: .photoId, value: true}) | from_entries) as $set
  | map(select($set[.photoId] | not))' "$TMP/archivos.json" >"$TMP/archivos-quedan.json"
CONTADORES_PROYECTADOS=$(calcular_contadores <"$TMP/archivos-quedan.json")

printf 'Sesiones de subida que coinciden (%s):\n' "$N_SESIONES"
jq -r '.[] | "  sub#\(.token[0:8])…  \"\(.nombre)\"  archivos=\(.archivos)  creada=\(.creadaEn)"' "$TMP/sesiones.json"
printf '\nArchivos de esas sesiones en %s (%s)' "$TABLA_FOTOS" "$N_ARCHIVOS"
if [ "$N_ARCHIVOS" -gt 0 ]; then
  printf ': %s\n' "$(jq -r 'group_by(.estado) | map("\(.[0].estado // "?")=\(length)") | join(", ")' "$TMP/archivos-borrar.json")"
  jq -r '.[] | "  \(.estado)  \(.tipo // "?")  \(.modo // "?")  \(.photoId)  \"\(.nombre)\""' "$TMP/archivos-borrar.json"
else
  printf '\n'
fi
if [ "$N_OTRO_NOMBRE" -gt 0 ]; then
  aviso "Archivos de esas sesiones con un nombre que NO empieza por \"$PREFIJO\" (la sesión cambió de nombre después): $N_OTRO_NOMBRE. Se borran igual porque pertenecen a una sesión que coincide:"
  jq -r '.[] | "  \(.estado)  \(.photoId)  \"\(.nombre)\""' "$TMP/archivos-otro-nombre.json" >&2
fi
printf '\nObjetos en s3://%s/%s (%s: %s originales, %s miniaturas)\n' \
  "$S3_BUCKET" "$PREFIJO_S3" "$N_OBJETOS" "$N_ORIGINALES" "$N_MINIATURAS"
jq -r '.[] | "  \(.key)  (\(.size) B)"' "$TMP/objetos-borrar.json"
printf 'Cargas multipart pendientes de esos archivos (%s)\n' "$N_MULTIPARTS"
jq -r '.[] | "  \(.key)  uploadId=\(.uploadId[0:8])…  iniciada=\(.iniciado)"' "$TMP/multiparts-borrar.json"
printf '\nContadores de %s en %s:\n' "$EVENT_ID" "$TABLA_EVENTO"
printf '  ahora:                  %s\n' "$(formatear_contadores "$EVENTO_ANTES")"
printf '  con lo que queda:       %s\n' "$(formatear_contadores "$CONTADORES_PROYECTADOS")"

if [ "$MODO" = "dry-run" ]; then
  printf '\n== Resumen (DRY-RUN, no se borró nada) ==\n'
  printf '  Sesiones que borraría:          %s\n' "$N_SESIONES"
  printf '  Ítems de archivos que borraría: %s\n' "$N_ARCHIVOS"
  printf '  Objetos de S3 que borraría:     %s (%s originales, %s miniaturas)\n' "$N_OBJETOS" "$N_ORIGINALES" "$N_MINIATURAS"
  printf '  Multipart que abortaría:        %s\n' "$N_MULTIPARTS"
  printf '  Contadores que quedarían:       %s\n' "$(formatear_contadores "$CONTADORES_PROYECTADOS")"
  printf '\nSi la lista es correcta, para borrar de verdad:\n  %s --ejecutar %q\n' "$0" "$PREFIJO"
  exit 0
fi

# =========================================================================================
# Ejecución
# =========================================================================================

printf '\n>> Abortando cargas multipart pendientes...\n'
while IFS=$'\t' read -r clave upload_id; do
  [ -z "$clave" ] && continue
  aws s3api abort-multipart-upload --bucket "$S3_BUCKET" --key "$clave" --upload-id "$upload_id" </dev/null >/dev/null
  printf '   abortada: %s\n' "$clave"
done < <(jq -r '.[] | [.key, .uploadId] | @tsv' "$TMP/multiparts-borrar.json")

printf '>> Borrando objetos de S3...\n'
jq -c 'map(.key)' "$TMP/objetos-borrar.json" >"$TMP/objetos-claves.json"
borrar_objetos "$TMP/objetos-claves.json"

# Comprobar que no quedó nada de lo que se iba a borrar.
listar_objetos >"$TMP/objetos-despues.json"
listar_multiparts >"$TMP/multiparts-despues.json"
RESTAN=$(jq -n --slurpfile c "$TMP/claves.json" --slurpfile o "$TMP/objetos-despues.json" \
  --slurpfile m "$TMP/multiparts-despues.json" '
  ($c[0] | map({key: ., value: true}) | from_entries) as $set
  | ([$o[0][] | select($set[.key])] | length) + ([$m[0][] | select($set[.key])] | length)')
[ "$RESTAN" -eq 0 ] || die 1 "Después de borrar siguen existiendo $RESTAN objeto(s) o multipart de esos archivos en S3."

printf '>> Borrando ítems de archivos de %s...\n' "$TABLA_FOTOS"
jq -c 'map(.photoId)' "$TMP/archivos-borrar.json" >"$TMP/archivos-ids.json"
borrar_items "$TABLA_FOTOS" photoId "$TMP/archivos-ids.json"

printf '>> Recalculando contadores de %s en %s...\n' "$EVENT_ID" "$TABLA_EVENTO"
# Se lee el ítem justo antes del recálculo; la escritura es condicional a que no haya cambiado.
EVENTO_PREVIO=$(leer_evento)
[ "$EVENTO_PREVIO" = "null" ] && die 1 "El ítem '$EVENT_ID' de $TABLA_EVENTO dejó de existir."
leer_archivos >"$TMP/archivos-despues.json"
CONTADORES_FINALES=$(calcular_contadores <"$TMP/archivos-despues.json")
actualizar_contadores "$CONTADORES_FINALES" "$EVENTO_PREVIO"

printf '>> Borrando sesiones de %s...\n' "$TABLA_SESIONES"
jq -c 'map(.id)' "$TMP/sesiones.json" >"$TMP/sesiones-ids.json"
borrar_items "$TABLA_SESIONES" id "$TMP/sesiones-ids.json"

EVENTO_DESPUES=$(leer_evento)

printf '\n== Resumen (EJECUTADO) ==\n'
printf '  Sesiones borradas:           %s\n' "$N_SESIONES"
printf '  Ítems de archivos borrados:  %s\n' "$N_ARCHIVOS"
printf '  Objetos de S3 borrados:      %s (%s originales, %s miniaturas)\n' "$N_OBJETOS" "$N_ORIGINALES" "$N_MINIATURAS"
printf '  Multipart abortados:         %s\n' "$N_MULTIPARTS"
printf '  Contadores antes:            %s\n' "$(formatear_contadores "$EVENTO_ANTES")"
printf '  Contadores finales:          %s\n' "$(formatear_contadores "$EVENTO_DESPUES")"
