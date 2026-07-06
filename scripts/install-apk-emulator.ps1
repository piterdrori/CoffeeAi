#Requires -Version 5.1
<#
.SYNOPSIS
  Build debug APK and install on a connected Android device or emulator (no phone upload needed).
.EXAMPLE
  .\scripts\install-apk-emulator.ps1
  .\scripts\install-apk-emulator.ps1 -Launch
#>
param(
    [switch]$Launch,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$PublishScript = Join-Path $Root "scripts\publish-apk.ps1"
$ApkPath = Join-Path $Root "backend\data\releases\personal-edge-ai.apk"
$Package = "com.personaledge.ai"

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb. Install Android SDK platform-tools or set ANDROID_HOME."
}

if (-not $SkipBuild) {
    & $PublishScript
}

$devices = & $adb devices | Select-String "device$" | Where-Object { $_ -notmatch "List of devices" }
if (-not $devices) {
    Write-Host ""
    Write-Host "No Android device/emulator connected." -ForegroundColor Yellow
    Write-Host "Start an emulator in Android Studio (Device Manager), then re-run:" -ForegroundColor Yellow
    Write-Host "  .\scripts\install-apk-emulator.ps1 -SkipBuild" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Or open the android/ folder in Android Studio and click Run (green play)." -ForegroundColor Yellow
    exit 1
}

Write-Host "==> Installing APK on device..." -ForegroundColor Cyan
& $adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE" }

Write-Host "Installed successfully." -ForegroundColor Green

if ($Launch) {
    & $adb shell am start -n "$Package/.MainActivity"
    Write-Host "Launched $Package" -ForegroundColor Green
}

Write-Host ""
Write-Host "Tip: Use Android Studio Device Manager for fastest iteration (Run button rebuilds + installs)." -ForegroundColor DarkGray
