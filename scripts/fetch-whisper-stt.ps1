#Requires -Version 5.1
<#
.SYNOPSIS
  Fetch whisper.cpp source + ggml-base.en model for offline STT.
.EXAMPLE
  .\scripts\fetch-whisper-stt.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AndroidDir = Join-Path $Root "android"
$WhisperSrc = Join-Path $AndroidDir "third_party\whisper.cpp"
$WhisperPy = Join-Path $Root "scripts\download-whisper-model.py"

if (-not (Test-Path (Join-Path $WhisperSrc "src\whisper.cpp"))) {
    Write-Host "Cloning whisper.cpp..." -ForegroundColor Cyan
    if (Test-Path $WhisperSrc) { Remove-Item -Recurse -Force $WhisperSrc }
    git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git $WhisperSrc
    if ($LASTEXITCODE -ne 0) { throw "git clone whisper.cpp failed" }
    Write-Host "whisper.cpp source ready." -ForegroundColor Green
} else {
    Write-Host "whisper.cpp source already present." -ForegroundColor Green
}

Write-Host "Ensuring ggml-base.en.bin model..." -ForegroundColor Cyan
python $WhisperPy
if ($LASTEXITCODE -ne 0) { throw "Whisper model download failed" }

Write-Host "Whisper STT assets installed." -ForegroundColor Green
