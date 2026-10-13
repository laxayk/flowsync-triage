# Runs both labelled eval sets against the running app and prints accuracy plus every miss.
# Use it after setting LLM_PROVIDER=groq and LLM_API_KEY in .env and restarting the backend.
# Compare with the keyword fallback by setting LLM_PROVIDER=none and running it again.
param([string]$Base = 'http://localhost:4200', [string]$User = 'approver')
$ErrorActionPreference = 'Stop'

# The API needs a login. Use APPROVER_PASSWORD from .env when set, else the demo default.
$pw = 'approver123'
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (Test-Path $envFile) {
    $line = Get-Content $envFile | Where-Object { $_ -match '^\s*APPROVER_PASSWORD\s*=' } | Select-Object -First 1
    if ($line) { $v = ($line -split '=', 2)[1].Trim(); if ($v) { $pw = $v } }
}
$login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" -ContentType 'application/json' `
    -Body (@{ username = $User; password = $pw } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

foreach ($set in 'basic', 'messy') {
    $r = Invoke-RestMethod -Uri "$Base/api/eval?set=$set" -Headers $headers -TimeoutSec 600
    Write-Host ("[{0}] samples={1} disposition={2} category={3} elapsedMs={4}" -f $set, $r.samples, $r.dispositionAccuracy, $r.categoryAccuracy, $r.elapsedMs) -ForegroundColor Cyan
    $via = $r.rows | Group-Object via | ForEach-Object { "$($_.Name)=$($_.Count)" }
    Write-Host ("  classified via: " + ($via -join ', '))
    foreach ($row in ($r.rows | Where-Object { -not $_.ok })) {
        Write-Host ("  MISS {0}: expected {1}/{2}, got {3}/{4} ({5})" -f $row.file, $row.expectedDisposition, $row.expectedCategory, $row.gotDisposition, $row.gotCategory, $row.via) -ForegroundColor Yellow
    }
}
Write-Host 'Tip: "via" shows llm:<model> when the real model answered and heuristic when the fallback did.'
