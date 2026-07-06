#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Backend = Join-Path $Root "backend"
$VenvPython = Join-Path $Backend ".venv\Scripts\python.exe"
$VenvUvicorn = Join-Path $Backend ".venv\Scripts\uvicorn.exe"

if (-not (Test-Path $VenvUvicorn)) {
    Write-Host "Setting up backend (first run)..." -ForegroundColor Cyan
    Push-Location $Backend
    try {
        if (-not (Test-Path .venv)) { python -m venv .venv }
        .\.venv\Scripts\pip.exe install -r requirements.txt
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path (Join-Path $Backend ".env"))) {
    Copy-Item (Join-Path $Backend ".env.example") (Join-Path $Backend ".env")
}

Write-Host "Starting backend at http://localhost:8080" -ForegroundColor Green
Push-Location $Backend
& $VenvUvicorn main:app --host 0.0.0.0 --port 8080
