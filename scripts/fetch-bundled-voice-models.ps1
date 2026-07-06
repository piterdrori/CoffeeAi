#Requires -Version 5.1
<#
.SYNOPSIS
  Download bundled Sherpa STT + Piper TTS models into APK assets.
.DESCRIPTION
  - STT: sherpa-onnx-streaming-zipformer-en-2023-06-26 (high-quality English streaming)
  - TTS: vits-piper-en_US-lessac-medium (natural Piper voice)

  Places files under android/app/src/main/assets/voice/
.EXAMPLE
  .\scripts\fetch-bundled-voice-models.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AssetsRoot = Join-Path $Root "android\app\src\main\assets\voice"
$CacheDir = Join-Path $Root "backend\data\cache"
$SttAssetDir = Join-Path $AssetsRoot "stt\sherpa-onnx-streaming-zipformer-en-2023-06-26"
$TtsAssetDir = Join-Path $AssetsRoot "tts\vits-piper-en_US-lessac-medium"

$SttArchive = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
$TtsArchive = "vits-piper-en_US-lessac-medium.tar.bz2"
$SttUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$SttArchive"
$TtsUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$TtsArchive"

function Test-SttModel([string]$Dir) {
    return (Test-Path (Join-Path $Dir "encoder-epoch-99-avg-1-chunk-16-left-128.onnx")) -and
        (Test-Path (Join-Path $Dir "tokens.txt"))
}

function Test-TtsModel([string]$Dir) {
    return (Test-Path (Join-Path $Dir "en_US-lessac-medium.onnx")) -and
        (Test-Path (Join-Path $Dir "espeak-ng-data"))
}

New-Item -ItemType Directory -Force -Path $CacheDir, $AssetsRoot | Out-Null

if (Test-SttModel $SttAssetDir) {
    Write-Host "STT model already present: $SttAssetDir" -ForegroundColor Green
} else {
    $sttZip = Join-Path $CacheDir $SttArchive
    if (-not (Test-Path $sttZip)) {
        Write-Host "Downloading STT model..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $SttUrl -OutFile $sttZip -UseBasicParsing
    }
    $extractDir = Join-Path $CacheDir "stt-extract"
    if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    tar -xjf $sttZip -C $extractDir
    $src = Join-Path $extractDir "sherpa-onnx-streaming-zipformer-en-2023-06-26"
    if (-not (Test-Path $src)) { throw "Unexpected STT archive layout" }
    if (Test-Path $SttAssetDir) { Remove-Item -Recurse -Force $SttAssetDir }
    New-Item -ItemType Directory -Force -Path (Split-Path $SttAssetDir) | Out-Null
    Copy-Item -Path $src -Destination $SttAssetDir -Recurse
    Remove-Item -Recurse -Force (Join-Path $SttAssetDir "test_wavs") -ErrorAction SilentlyContinue
    Get-ChildItem $SttAssetDir -Filter "*.sh" | Remove-Item -Force -ErrorAction SilentlyContinue
    Remove-Item -Force (Join-Path $SttAssetDir "README.md") -ErrorAction SilentlyContinue
    if (-not (Test-SttModel $SttAssetDir)) { throw "STT model install failed" }
    Write-Host "STT model ready." -ForegroundColor Green
}

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

Write-Host "Bundled voice models installed under assets/voice" -ForegroundColor Green
