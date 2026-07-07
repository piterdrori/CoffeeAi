#Requires -Version 5.1
<#
.SYNOPSIS
  Download bundled Piper TTS model into APK assets.
.EXAMPLE
  .\scripts\fetch-bundled-voice-models.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AssetsRoot = Join-Path $Root "android\app\src\main\assets\voice"
$CacheDir = Join-Path $Root "backend\data\cache"
$TtsAssetDir = Join-Path $AssetsRoot "tts\vits-piper-en_US-lessac-medium"

$TtsArchive = "vits-piper-en_US-lessac-medium.tar.bz2"
$TtsUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$TtsArchive"

function Test-TtsModel([string]$Dir) {
    return (Test-Path (Join-Path $Dir "en_US-lessac-medium.onnx")) -and
        (Test-Path (Join-Path $Dir "espeak-ng-data"))
}

New-Item -ItemType Directory -Force -Path $CacheDir, $AssetsRoot | Out-Null

if (Test-TtsModel $TtsAssetDir) {
    Write-Host "TTS model already present: $TtsAssetDir" -ForegroundColor Green
} else {
    $ttsZip = Join-Path $CacheDir $TtsArchive
    if (-not (Test-Path $ttsZip)) {
        Write-Host "Downloading TTS model..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $TtsUrl -OutFile $ttsZip -UseBasicParsing
    }
    $extractDir = Join-Path $CacheDir "tts-extract"
    if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    tar -xjf $ttsZip -C $extractDir
    $src = Join-Path $extractDir "vits-piper-en_US-lessac-medium"
    if (-not (Test-Path $src)) { throw "Unexpected TTS archive layout" }
    if (Test-Path $TtsAssetDir) { Remove-Item -Recurse -Force $TtsAssetDir }
    New-Item -ItemType Directory -Force -Path (Split-Path $TtsAssetDir) | Out-Null
    Copy-Item -Path $src -Destination $TtsAssetDir -Recurse
    if (-not (Test-TtsModel $TtsAssetDir)) { throw "TTS model install failed" }
    Write-Host "TTS model ready." -ForegroundColor Green
}

Write-Host "Bundled TTS model installed under assets/voice" -ForegroundColor Green
