# Compila y ejecuta el backend contra DynamoDB Local y el S3 falso (local/README.md, paso 3).
# Queda en primer plano: Ctrl+C lo detiene. Con -SinCompilar usa el .jar que ya existe.
# Con -Celular (o -IpLan <ip>) firma las URLs del S3 falso con la IP del PC en la red local, para
# que un celular en el mismo WiFi pueda subir y ver archivos (local/README.md, sección 9).
param(
    [switch] $SinCompilar,
    [switch] $Celular,
    [string] $IpLan
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\comun.ps1"

$raiz = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $raiz 'backend'

# JDK 25 (Corretto, el mismo de producción) y Maven: los que ya instaló IntelliJ / el wrapper.
$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'corretto-25*' -ErrorAction SilentlyContinue |
    Where-Object { -not $_.Name.StartsWith('.') } | Select-Object -First 1
if (-not $jdk) { throw "No encontré un JDK 25 en $env:USERPROFILE\.jdks (Corretto 25). Instálalo desde IntelliJ." }
$env:JAVA_HOME = $jdk.FullName
$java = Join-Path $jdk.FullName 'bin\java.exe'

if (-not $SinCompilar) {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    $mvnRuta = if ($mvn) { $mvn.Source } else {
        (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue |
            Select-Object -First 1).FullName
    }
    if (-not $mvnRuta) { throw 'No encontré Maven (mvn). Instálalo o compílalo una vez desde IntelliJ.' }
    Write-Host "Compilando con $mvnRuta ..."
    & $mvnRuta -q -f (Join-Path $backend 'pom.xml') package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'La compilación falló.' }
}

if ($Celular -and -not $IpLan) {
    # La IPv4 de la interfaz que tiene puerta de enlace (el WiFi o el cable hacia el router).
    $IpLan = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Select-Object -First 1).IPv4Address.IPAddress
    if (-not $IpLan) { throw 'No encontré la IP del PC en la red local. Pásala con -IpLan 192.168.x.y' }
}
if ($IpLan) {
    $Global:S3Local = "http://${IpLan}:9090"
}

# Mismas variables que boda.service en la EC2 (research R10), apuntando a lo local.
$env:EVENT_ID = $Global:EventoLocal
$env:S3_BUCKET = $Global:BucketLocal
$env:S3_ENDPOINT = $Global:S3Local
$env:AWS_ENDPOINT_URL_DYNAMODB = $Global:DynamoLocal

Write-Host "Backend en http://localhost:8080 (evento $env:EVENT_ID). Ctrl+C para detenerlo." -ForegroundColor Green
Write-Host "URLs de archivos firmadas contra $env:S3_ENDPOINT"
if ($IpLan) {
    Write-Host ''
    Write-Host 'Desde el celular (mismo WiFi), con los ng serve arrancados con --host 0.0.0.0:' -ForegroundColor Cyan
    Write-Host "  Invitados: http://${IpLan}:4200"
    Write-Host "  Novios:    http://${IpLan}:4201"
    Write-Host ''
}
& $java -jar (Join-Path $backend 'target\app.jar')
