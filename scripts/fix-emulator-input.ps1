#Requires -Version 5.1
<#
.SYNOPSIS
  Fix PC keyboard + microphone routing for the EdgeAI Android emulator.
.EXAMPLE
  .\scripts\fix-emulator-input.ps1
  .\scripts\fix-emulator-input.ps1 -Restart
#>
param(
    [switch]$Restart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$AvdName = "EdgeAI_Pixel6"
$AvdDir = Join-Path $env:USERPROFILE ".android\avd\${AvdName}.avd"
$ConfigIni = Join-Path $AvdDir "config.ini"
$QemuIni = Join-Path $AvdDir "hardware-qemu.ini"

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"

if (-not (Test-Path $ConfigIni)) {
    throw "AVD not found at $AvdDir. Run .\scripts\setup-android-emulator.ps1 first."
}

function Set-IniValue {
    param([string]$Content, [string]$Key, [string]$Value)
    if ($Content -match "(?m)^$Key\s*=") {
        return ($Content -replace "(?m)^$Key\s*=.*", "$Key = $Value")
    }
    return ($Content.TrimEnd() + "`n$Key = $Value`n")
}

Write-Host "==> Enabling PC keyboard in AVD config..." -ForegroundColor Cyan
$content = Get-Content $ConfigIni -Raw
$content = Set-IniValue $content "hw.keyboard" "yes"
$content = Set-IniValue $content "hw.keyboard.lid" "no"
Set-Content -Path $ConfigIni -Value $content -NoNewline
Write-Host "   hw.keyboard=yes, hw.keyboard.lid=no" -ForegroundColor Green

if (Test-Path $QemuIni) {
    $qemu = Get-Content $QemuIni -Raw
    $qemu = $qemu -replace "hw\.keyboard\s*=\s*false", "hw.keyboard = true"
    $qemu = $qemu -replace "hw\.keyboard\.lid\s*=\s*true", "hw.keyboard.lid = false"
    Set-Content -Path $QemuIni -Value $qemu -NoNewline
    Write-Host "   Updated hardware-qemu.ini" -ForegroundColor Green
}

$devices = @()
if (Test-Path $adb) {
    $online = & $adb devices | Select-String "emulator-\d+\s+device$"
    if ($online) { $devices = $online }
}

if ($devices) {
    Write-Host "==> Enabling host microphone on running emulator..." -ForegroundColor Cyan
    $micResult = & $adb emu avd hostmicon 2>&1 | Out-String
    if ($micResult -match "OK") {
        Write-Host "   Host mic enabled" -ForegroundColor Green
    } else {
        Write-Host "   hostmicon: $micResult" -ForegroundColor Yellow
    }
    & $adb shell settings put secure show_ime_with_hard_keyboard 0 2>$null
    & $adb shell settings put secure default_input_method "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME" 2>$null
    Write-Host "   PC keyboard routing configured" -ForegroundColor Green
} else {
    Write-Host "No online emulator - run again after boot." -ForegroundColor Yellow
}

if ($Restart) {
    if (-not (Test-Path $emulator)) { throw "emulator.exe not found" }
    Write-Host "==> Cold-starting emulator (keyboard fix needs fresh boot)..." -ForegroundColor Cyan
    if ($devices) {
        & $adb emu kill 2>$null
        Start-Sleep -Seconds 4
    }
    Start-Process -FilePath $emulator -ArgumentList @(
        "-avd", $AvdName,
        "-allow-host-audio",
        "-no-snapshot-load"
    ) -WindowStyle Normal
    Write-Host "   Started with -allow-host-audio -no-snapshot-load" -ForegroundColor Green
    Write-Host "   Wait for home screen, then run: .\scripts\fix-emulator-input.ps1" -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "If keyboard still fails, cold-restart:" -ForegroundColor Yellow
    Write-Host "  .\scripts\fix-emulator-input.ps1 -Restart" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "Test:" -ForegroundColor Green
Write-Host "  1. Click emulator window FIRST, then type in chat" -ForegroundColor Cyan
Write-Host "  2. Voice mode should show Engine: Android speech (high accuracy)" -ForegroundColor Cyan
Write-Host "  3. Speak clearly; green dot = mic active" -ForegroundColor Cyan
Write-Host ""
