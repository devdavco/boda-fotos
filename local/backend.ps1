# Compila y ejecuta el backend contra DynamoDB Local y el S3 falso (local/README.md, paso 3).
# Queda en primer plano: Ctrl+C lo detiene. Con -SinCompilar usa el .jar que ya existe.
param([switch] $SinCompilar)
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

# Mismas variables que boda.service en la EC2 (research R10), apuntando a lo local.
$env:EVENT_ID = $Global:EventoLocal
$env:S3_BUCKET = $Global:BucketLocal
$env:S3_ENDPOINT = $Global:S3Local
$env:AWS_ENDPOINT_URL_DYNAMODB = $Global:DynamoLocal

Write-Host "Backend en http://localhost:8080 (evento $env:EVENT_ID). Ctrl+C para detenerlo." -ForegroundColor Green
& $java -jar (Join-Path $backend 'target\app.jar')
