# Valores comunes de los scripts locales (local/README.md). Se carga con: . "$PSScriptRoot\comun.ps1"
# Credenciales falsas: DynamoDB Local y S3Mock aceptan cualquiera. Nunca se usan contra AWS porque
# todas las llamadas llevan --endpoint-url a localhost.

$env:AWS_ACCESS_KEY_ID = 'local'
$env:AWS_SECRET_ACCESS_KEY = 'local'
$env:AWS_REGION = 'us-east-1'
$env:AWS_DEFAULT_REGION = 'us-east-1'
# Que la CLI de AWS no use un perfil real del equipo por accidente.
Remove-Item Env:AWS_PROFILE -ErrorAction SilentlyContinue
Remove-Item Env:AWS_SESSION_TOKEN -ErrorAction SilentlyContinue

$Global:DynamoLocal = 'http://localhost:8000'
$Global:S3Local = 'http://localhost:9090'
$Global:BucketLocal = 'boda-fotos-local'
$Global:EventoLocal = 'boda-prueba'

# Hora de Cali (UTC-5, sin horario de verano), como la usa el backend.
function Get-AhoraCali { (Get-Date).ToUniversalTime().AddHours(-5) }
function Format-Local([datetime] $fecha) { $fecha.ToString('yyyy-MM-ddTHH:mm:ss') }

# Ejecuta la CLI de AWS contra DynamoDB Local con un JSON escrito en un archivo temporal (evita
# los problemas de comillas de PowerShell al pasar JSON a programas externos).
function Invoke-Dynamo([string] $operacion, [hashtable] $entrada) {
    $archivo = New-TemporaryFile
    try {
        $entrada | ConvertTo-Json -Depth 10 | Set-Content -Path $archivo -Encoding utf8NoBOM
        aws dynamodb $operacion --endpoint-url $Global:DynamoLocal --cli-input-json "file://$archivo" --output json
        if ($LASTEXITCODE -ne 0) { throw "Falló 'aws dynamodb $operacion'" }
    } finally {
        Remove-Item $archivo -ErrorAction SilentlyContinue
    }
}
