#Requires -Version 5.1
<#
.SYNOPSIS
  Print steps to route PC microphone, speakers, and keyboard through the Android emulator.
.EXAMPLE
  .\scripts\setup-emulator-audio.ps1
#>
Set-StrictMode -Version Latest

Write-Host ""
Write-Host "Android Emulator - use your PC mic, speakers, and keyboard" -ForegroundColor Green
Write-Host ""

Write-Host "1. MICROPHONE (PC mic -> emulator)" -ForegroundColor Cyan
Write-Host "   - Start the emulator"
Write-Host "   - Click the three dots (...) on the emulator toolbar -> Extended controls"
Write-Host "   - Open Microphone"
Write-Host "   - Enable Virtual microphone uses host audio input"
Write-Host "   - Speak into your PC mic; green dot in status bar means the app is listening"
Write-Host ""

Write-Host "2. SPEAKERS (emulator -> PC speakers)" -ForegroundColor Cyan
Write-Host "   - Emulator audio usually plays on your PC speakers by default"
Write-Host "   - Turn up Windows volume AND the emulator side-panel volume slider"
Write-Host "   - In the app, TTS auto-boosts media volume when replies are read aloud"
Write-Host ""

Write-Host "3. KEYBOARD (PC keyboard -> emulator)" -ForegroundColor Cyan
Write-Host "   - Physical keyboard works when the emulator window is focused"
Write-Host "   - Type in chat; Enter sends in most fields"
Write-Host "   - If keys do nothing: Emulator Settings -> General -> Enable keyboard input"
Write-Host ""

Write-Host "4. OFFLINE VOICE (already in the app)" -ForegroundColor Cyan
Write-Host "   - STT: Vosk model (run .\scripts\setup-vosk-emulator.ps1 once)"
Write-Host "   - TTS: built-in offline engine on the device (no cloud)"
Write-Host ""

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (Test-Path $adb) {
    $devices = & $adb devices | Select-String "device$" | Where-Object { $_ -notmatch "List of devices" }
    if ($devices) {
        Write-Host "Hiding on-screen keyboard when PC keyboard is used..." -ForegroundColor DarkGray
        & $adb shell settings put secure show_ime_with_hard_keyboard 0 2>$null
        Write-Host "Done." -ForegroundColor DarkGray
    }
}
Write-Host ""
