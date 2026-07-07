# Start CoffeeAI emulator with host PC mic + speaker audio enabled.
# Android emulators block host audio by default; -allow-host-audio is required.

$ErrorActionPreference = "Stop"
$sdk = $env:LOCALAPPDATA + "\Android\Sdk"
$emu = Join-Path $sdk "emulator\emulator.exe"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$avd = if ($args.Count -gt 0) { $args[0] } else { "CoffeeAI_Large" }

if (-not (Test-Path $emu)) {
    Write-Error "Emulator not found at $emu"
}

Write-Host "Stopping any running emulators..."
& $adb emu kill 2>$null
Start-Sleep -Seconds 2

Write-Host "Starting AVD '$avd' with -allow-host-audio (PC mic + speakers)..."
Start-Process -FilePath $emu -ArgumentList @("-avd", $avd, "-allow-host-audio") -WindowStyle Normal

Write-Host "Waiting for device..."
& $adb wait-for-device
Start-Sleep -Seconds 15

Write-Host "Enabling host mic routing..."
& $adb emu avd hostmicon 2>&1 | Out-Null

Write-Host "Setting emulator media volume to maximum..."
& $adb shell cmd media_session volume --stream 3 --set 15 2>&1 | Out-Null
1..8 | ForEach-Object { & $adb shell input keyevent 24 2>&1 | Out-Null; Start-Sleep -Milliseconds 150 }
& $adb shell cmd media_session volume --stream 3 --get 2>&1

Write-Host ""
Write-Host "Done. Also check:"
Write-Host "  1. Emulator side panel: ... -> Microphone -> Virtual microphone uses host audio input"
Write-Host "  2. Press the emulator VOLUME UP button (under the power button) until max"
Write-Host "  3. Windows Volume Mixer: raise 'qemu-system-x86_64' (or Android Emulator) — often muted/low"
Write-Host "  4. Windows: Settings -> System -> Sound -> raise volume for your speakers"
