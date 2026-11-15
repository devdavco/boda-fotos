# Levanta DynamoDB Local y el S3 falso, crea las tablas y carga el evento de prueba y las cuentas
# de los novios (local/README.md, paso 2). Se puede repetir: si ya estaba arriba, lo reinicia vacío.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\comun.ps1"

Write-Host '1/4  Levantando DynamoDB Local (puerto 8000) y S3 falso (puerto 9090)...'
# docker compose escribe su progreso en stderr; en Windows PowerShell 5.1, con
# $ErrorActionPreference = 'Stop', eso se convierte en una excepción. Se ejecuta con 'Continue'
# y se revisa el código de salida.
$ErrorActionPreference = 'Continue'
docker compose -f "$PSScriptRoot\docker-compose.yml" down --remove-orphans 2>&1 | Out-Null
docker compose -f "$PSScriptRoot\docker-compose.yml" up -d 2>&1 | ForEach-Object { "$_" }
$codigo = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($codigo -ne 0) { throw '¿Docker Desktop está abierto? docker compose no pudo arrancar.' }

foreach ($i in 1..60) {
    $dynamo = $false; $s3 = $false
    $ErrorActionPreference = 'Continue'
    aws dynamodb list-tables --endpoint-url $Global:DynamoLocal --output text 2>&1 | Out-Null
    $dynamo = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = 'Stop'
    try {
        $s3 = (Invoke-WebRequest "$Global:S3Local/$Global:BucketLocal" -Method Head -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200
    } catch {}
    if ($dynamo -and $s3) { break }
    if ($i -eq 60) { throw 'DynamoDB Local o el S3 falso no respondieron en 2 minutos.' }
    Start-Sleep -Seconds 2
}

Write-Host '2/4  Creando las tablas (las mismas de data-model.md)...'
$tablas = @(
    @{ TableName = 'boda-evento';   Clave = 'eventoId' },
    @{ TableName = 'boda-admins';   Clave = 'usuario' },
    @{ TableName = 'boda-sesiones'; Clave = 'id' }
)
foreach ($t in $tablas) {
    Invoke-Dynamo 'create-table' @{
        TableName            = $t.TableName
        BillingMode          = 'PAY_PER_REQUEST'
        AttributeDefinitions = @(@{ AttributeName = $t.Clave; AttributeType = 'S' })
        KeySchema            = @(@{ AttributeName = $t.Clave; KeyType = 'HASH' })
    } | Out-Null
}
Invoke-Dynamo 'create-table' @{
    TableName              = 'boda-fotos'
    BillingMode            = 'PAY_PER_REQUEST'
    AttributeDefinitions   = @(
        @{ AttributeName = 'photoId'; AttributeType = 'S' },
        @{ AttributeName = 'galeriaEvento'; AttributeType = 'S' },
        @{ AttributeName = 'galeriaOrden'; AttributeType = 'S' }
    )
    KeySchema              = @(@{ AttributeName = 'photoId'; KeyType = 'HASH' })
    GlobalSecondaryIndexes = @(@{
        IndexName  = 'galeria-index'
        KeySchema  = @(
            @{ AttributeName = 'galeriaEvento'; KeyType = 'HASH' },
            @{ AttributeName = 'galeriaOrden'; KeyType = 'RANGE' }
        )
        Projection = @{ ProjectionType = 'ALL' }
    })
} | Out-Null

Write-Host '3/4  Creando las cuentas de prueba mara y daniel (contraseña: clave-de-prueba-1)...'
# Hash bcrypt de costo 12 de "clave-de-prueba-1". Solo para este entorno local: en AWS cada cuenta
# tiene su propio hash generado con `java -jar app.jar hash-contrasena`.
$hashPrueba = '$2a$12$B3gnYWWfWQ38ia6tQTXVp.QwJNynMq6333zAYj2K/YnKIsUuyFYvO'
foreach ($cuenta in @(@('mara', 'Mara'), @('daniel', 'Daniel'))) {
    Invoke-Dynamo 'put-item' @{
        TableName = 'boda-admins'
        Item      = @{
            usuario          = @{ S = $cuenta[0] }
            nombreVisible    = @{ S = $cuenta[1] }
            hashContrasena   = @{ S = $hashPrueba }
            intentosFallidos = @{ N = '0' }
        }
    } | Out-Null
}

Write-Host "4/4  Creando el evento '$Global:EventoLocal' con la recepción abierta..."
$hoy = (Get-AhoraCali).Date
Invoke-Dynamo 'put-item' @{
    TableName = 'boda-evento'
    Item      = @{
        eventoId             = @{ S = $Global:EventoLocal }
        aperturaLocal        = @{ S = (Format-Local $hoy.AddDays(-1)) }
        cierreLocal          = @{ S = (Format-Local $hoy.AddDays(7).AddHours(12)) }
        disponibleHastaLocal = @{ S = (Format-Local $hoy.AddDays(14).AddSeconds(-1)) }
        maxArchivosPorSesion = @{ N = '300' }
        maxBytesPorSesion    = @{ N = '5368709120' }
        archivosTotal        = @{ N = '0' }
        fotos                = @{ N = '0' }
        videos               = @{ N = '0' }
    }
} | Out-Null

Write-Host ''
Write-Host 'Listo. DynamoDB Local en http://localhost:8000 y S3 falso en http://localhost:9090' -ForegroundColor Green
Write-Host 'Siguiente paso: .\local\backend.ps1'
