# FlowSync Triage: one-command start on Windows (PowerShell 5.1+, Docker Desktop running).
#   .\run.ps1            build and start everything, then open the dashboard
#   .\run.ps1 -Down      stop everything
#   .\run.ps1 -Logs      follow backend logs
param([switch]$Down, [switch]$Logs)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host 'Docker is not installed or not on PATH. Install Docker Desktop, start it, and retry.' -ForegroundColor Red
    exit 1
}
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker Desktop is not running. Start it, wait until it says "Engine running", and retry.' -ForegroundColor Red
    exit 1
}

if ($Down) { docker compose down; exit $LASTEXITCODE }
if ($Logs) { docker compose logs -f backend; exit $LASTEXITCODE }

if (-not (Test-Path .env)) {
    Copy-Item .env.example .env
    Write-Host 'Created .env from .env.example (LLM off, replies captured, sample mailbox only).' -ForegroundColor Yellow
    Write-Host 'Edit .env later to add a Groq key or the test Gmail. Never commit it.' -ForegroundColor Yellow
}

docker compose up --build -d
if ($LASTEXITCODE -ne 0) { Write-Host 'docker compose failed. Run: docker compose logs' -ForegroundColor Red; exit 1 }

Write-Host 'Waiting for the backend to become healthy...'
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:4200/api/health' -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { Start-Sleep -Seconds 3 }
}
if (-not $ok) { Write-Host 'Backend did not come up in time. Run: .\run.ps1 -Logs' -ForegroundColor Red; exit 1 }

Write-Host 'Ready: http://localhost:4200   (agent / approver, passwords in .env)' -ForegroundColor Green
Start-Process 'http://localhost:4200'
