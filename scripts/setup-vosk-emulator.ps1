#Requires -Version 5.1
<#
.SYNOPSIS
  Download Vosk small English model and install it on a connected emulator/device for offline STT.
.EXAMPLE
  .\scripts\setup-vosk-emulator.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$CacheDir = Join-Path $Root "backend\data\cache"
$ZipName = "vosk-model-small-en-us-0.15.zip"
$ZipPath = Join-Path $CacheDir $ZipName
$ModelDir = Join-Path $CacheDir "vosk-model-small-en-us-0.15"
$ModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
$Package = "com.personaledge.ai"

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb. Install Android SDK platform-tools."
}

$devices = & $adb devices | Select-String "device$" | Where-Object { $_ -notmatch "List of devices" }
if (-not $devices) {
    throw "No Android device/emulator connected. Start the emulator first."
}

New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

if (-not (Test-Path (Join-Path $ModelDir "am"))) {
    if (-not (Test-Path $ZipPath)) {
        Write-Host "==> Downloading Vosk model (~40 MB)..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $ModelUrl -OutFile $ZipPath
    }
    if (Test-Path $ModelDir) { Remove-Item -Recurse -Force $ModelDir }
    Write-Host "==> Extracting..." -ForegroundColor Cyan
    Expand-Archive -Path $ZipPath -DestinationPath $CacheDir -Force
}

if (-not (Test-Path (Join-Path $ModelDir "am"))) {
    throw "Vosk model extraction failed. Expected am folder in $ModelDir"
}

Write-Host "==> Pushing model to device..." -ForegroundColor Cyan
& $adb shell "rm -rf /data/local/tmp/vosk-model" | Out-Null
& $adb push "$ModelDir" /data/local/tmp/vosk-model
if ($LASTEXITCODE -ne 0) { throw "adb push failed" }

& $adb shell "run-as $Package sh -c 'rm -rf files/vosk-model && cp -r /data/local/tmp/vosk-model files/vosk-model'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to copy model into app storage. Install the app first: .\scripts\install-apk-emulator.ps1"
}

Write-Host ""
Write-Host "Vosk STT model installed." -ForegroundColor Green
Write-Host "In the emulator:" -ForegroundColor Green
Write-Host "  1. Extended controls -> Microphone -> enable Virtual microphone" -ForegroundColor Cyan
Write-Host "  2. Chat -> mic icon -> tap orb to speak" -ForegroundColor Cyan
Write-Host ""
