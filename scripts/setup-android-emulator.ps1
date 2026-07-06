#Requires -Version 5.1
<#
.SYNOPSIS
  One-time setup: Android SDK emulator + a Pixel 6 AVD for local APK testing.
.EXAMPLE
  .\scripts\setup-android-emulator.ps1
  .\scripts\install-apk-emulator.ps1 -Launch
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }

$sdkmanager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkmanager)) {
    $sdkmanager = Join-Path $sdkRoot "cmdline-tools\bin\sdkmanager.bat"
}
if (-not (Test-Path $sdkmanager)) {
    Write-Host "Android SDK not found. Install Android Studio, open SDK Manager, then re-run." -ForegroundColor Red
    exit 1
}

Write-Host "==> Installing emulator system image (this may take several minutes)..." -ForegroundColor Cyan
& $sdkmanager --install "platform-tools" "emulator" "platforms;android-35" "system-images;android-35;google_apis;x86_64"

$avdmanager = Join-Path $sdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
if (-not (Test-Path $avdmanager)) {
    $avdmanager = Join-Path $sdkRoot "cmdline-tools\bin\avdmanager.bat"
}

$avdName = "EdgeAI_Pixel6"
$existing = & $avdmanager list avd 2>&1 | Out-String
if ($existing -match $avdName) {
    Write-Host "AVD '$avdName' already exists." -ForegroundColor Green
} else {
    Write-Host "==> Creating AVD '$avdName'..." -ForegroundColor Cyan
    echo "no" | & $avdmanager create avd -n $avdName -k "system-images;android-35;google_apis;x86_64" -d "pixel_6"
}

$fixInput = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "fix-emulator-input.ps1"
if (Test-Path $fixInput) {
    & $fixInput
}

$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
Write-Host ""
Write-Host "Setup complete. Start the emulator with:" -ForegroundColor Green
Write-Host "  & `"$emulator`" -avd $avdName -allow-host-audio" -ForegroundColor Cyan
Write-Host ""
Write-Host "Then install the app:" -ForegroundColor Green
Write-Host "  .\scripts\install-apk-emulator.ps1 -Launch" -ForegroundColor Cyan
Write-Host ""
Write-Host "Fastest loop: open android/ in Android Studio and click Run (green play)." -ForegroundColor DarkGray
