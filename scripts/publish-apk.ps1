#Requires -Version 5.1
<#
.SYNOPSIS
  Build debug APK and publish to backend/data/releases/ for WiFi download.
.EXAMPLE
  .\scripts\publish-apk.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AndroidDir = Join-Path $Root "android"
$ApkSource = Join-Path $AndroidDir "app\build\outputs\apk\debug\app-debug.apk"
$ReleasesDir = Join-Path $Root "backend\data\releases"
$ApkDest = Join-Path $ReleasesDir "personal-edge-ai.apk"
$MetaDest = Join-Path $ReleasesDir "release.json"
$GradleBat = Join-Path $AndroidDir "gradlew.bat"

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    $candidates = @(
        "C:\Program Files\Amazon Corretto\jdk17.0.19_10",
        "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot",
        "${env:ProgramFiles}\Android\Android Studio\jbr"
    )
    foreach ($dir in $candidates) {
        if (Test-Path "$dir\bin\java.exe") { return $dir }
    }
    $corretto = Get-ChildItem "C:\Program Files\Amazon Corretto" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($corretto) { return $corretto.FullName }
    return $null
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    throw "JAVA_HOME not set and no JDK found. Install JDK 17: winget install Amazon.Corretto.17.JDK"
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"
Write-Host "Using JAVA_HOME=$javaHome" -ForegroundColor DarkGray

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }
if (Test-Path $sdkRoot) {
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $localProps = Join-Path $AndroidDir "local.properties"
    $sdkDirEscaped = $sdkRoot -replace '\\', '\\'
    "sdk.dir=$sdkDirEscaped" | Set-Content -Path $localProps -Encoding ASCII
    Write-Host "Using ANDROID_SDK=$sdkRoot" -ForegroundColor DarkGray
}

if (Test-Path "C:\Windows\System32\ASProxy64.dll") {
    Write-Warning "ASProxy64.dll detected - may crash Java/Gradle. Run Astrill LSP uninstall or: RegisterLSP64.exe -f (as Admin)"
}

Write-Host "==> Building debug APK..." -ForegroundColor Cyan
if (-not (Test-Path $GradleBat)) {
    throw "gradlew.bat not found at $GradleBat. Open android/ in Android Studio first."
}

Push-Location $AndroidDir
try {
    & $GradleBat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path $ApkSource)) {
    throw "APK not found at $ApkSource"
}

New-Item -ItemType Directory -Force -Path $ReleasesDir | Out-Null
Copy-Item -Path $ApkSource -Destination $ApkDest -Force

$version = "1.0"
$buildGradle = Join-Path $AndroidDir "app\build.gradle.kts"
if (Test-Path $buildGradle) {
    $match = Select-String -Path $buildGradle -Pattern "versionName\s*=\s*`"([^`"]+)`"" | Select-Object -First 1
    if ($match) { $version = $match.Matches[0].Groups[1].Value }
}

$size = (Get-Item $ApkDest).Length
$meta = @{
    version    = $version
    filename   = "personal-edge-ai.apk"
    size_bytes = $size
    updated_at = (Get-Date).ToUniversalTime().ToString("o")
    source     = "app-debug.apk"
}
$json = $meta | ConvertTo-Json
[System.IO.File]::WriteAllText($MetaDest, $json, [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "Published successfully!" -ForegroundColor Green
Write-Host "  APK:  $ApkDest ($([math]::Round($size / 1MB, 2)) MB)"
Write-Host "  Meta: $MetaDest"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Start backend:  docker compose up -d   (or uvicorn in backend/)"
Write-Host "  2. On phone WiFi:  http://YOUR_PC_IP:8080  -> Download APK"
