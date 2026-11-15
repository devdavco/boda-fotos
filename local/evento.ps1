# Cambia la ventana del evento de prueba para ver cada estado (local/README.md, paso 7).
# Uso: .\local\evento.ps1 abierta | no-iniciada | cerrada | periodo-terminado | ver
param(
    [Parameter(Mandatory)]
    [ValidateSet('abierta', 'no-iniciada', 'cerrada', 'periodo-terminado', 'ver')]
    [string] $Estado
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\comun.ps1"

$clave = @{ eventoId = @{ S = $Global:EventoLocal } }

if ($Estado -eq 'ver') {
    (Invoke-Dynamo 'get-item' @{ TableName = 'boda-evento'; Key = $clave; ConsistentRead = $true } |
        ConvertFrom-Json).Item | ConvertTo-Json -Depth 5
    exit 0
}

$hoy = (Get-AhoraCali).Date
# apertura, cierre y fin de disponibilidad en hora de Cali para cada estado.
$fechas = switch ($Estado) {
    'abierta'           { $hoy.AddDays(-1), $hoy.AddDays(7).AddHours(12), $hoy.AddDays(14).AddSeconds(-1) }
    'no-iniciada'       { $hoy.AddDays(2),  $hoy.AddDays(5).AddHours(12), $hoy.AddDays(14).AddSeconds(-1) }
    'cerrada'           { $hoy.AddDays(-3), $hoy.AddDays(-1).AddHours(12), $hoy.AddDays(14).AddSeconds(-1) }
    'periodo-terminado' { $hoy.AddDays(-10), $hoy.AddDays(-5).AddHours(12), $hoy.AddDays(-1).AddSeconds(-1) }
}

# También quita un cierre anticipado hecho desde la galería, para poder volver a abrir.
Invoke-Dynamo 'update-item' @{
    TableName                 = 'boda-evento'
    Key                       = $clave
    UpdateExpression          = 'SET aperturaLocal = :a, cierreLocal = :c, disponibleHastaLocal = :d REMOVE cierreAnticipadoEn, cierreAnticipadoPor'
    ExpressionAttributeValues = @{
        ':a' = @{ S = (Format-Local $fechas[0]) }
        ':c' = @{ S = (Format-Local $fechas[1]) }
        ':d' = @{ S = (Format-Local $fechas[2]) }
    }
} | Out-Null

Write-Host "Evento '$Global:EventoLocal' en estado '$Estado'." -ForegroundColor Green
try {
    Invoke-RestMethod 'http://localhost:8080/api/recepcion/estado' | Format-List
} catch {
    Write-Host '(El backend no está corriendo; el cambio se verá cuando arranque.)'
}
