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

$voiceScript = Join-Path $Root "scripts\fetch-bundled-voice-models.ps1"
$whisperScript = Join-Path $Root "scripts\fetch-whisper-stt.ps1"
if (Test-Path $whisperScript) {
    Write-Host "==> Ensuring whisper.cpp source + base.en STT model..." -ForegroundColor Cyan
    try {
        & $whisperScript
    } catch {
        if (-not (Test-Path (Join-Path $Root "android\app\src\main\assets\voice\stt\ggml-base.en.bin"))) {
            throw "Whisper STT model missing. Run .\scripts\fetch-whisper-stt.ps1`n$($_.Exception.Message)"
        }
        Write-Warning "Could not refresh Whisper STT (using existing files): $($_.Exception.Message)"
    }
}
if (Test-Path $voiceScript) {
    Write-Host "==> Ensuring bundled TTS model in APK assets..." -ForegroundColor Cyan
    try {
        & $voiceScript
    } catch {
        if (-not (Test-Path (Join-Path $Root "android\app\src\main\assets\voice\tts"))) {
            throw "Bundled TTS model missing. Run .\scripts\fetch-bundled-voice-models.ps1`n$($_.Exception.Message)"
        }
        Write-Warning "Could not refresh TTS model (using existing files): $($_.Exception.Message)"
    }
}

$fetchScript = Join-Path $Root "scripts\fetch-bundled-gemma-model.ps1"
$bundledModel = Join-Path $Root "android\app\src\main\assets\models\gemma3-1b-it-int4.litertlm"
if (Test-Path $fetchScript) {
    Write-Host "==> Ensuring bundled Gemma 3 1B model in APK assets..." -ForegroundColor Cyan
    try {
        & $fetchScript
    } catch {
        if (-not (Test-Path $bundledModel)) {
            throw "Bundled Gemma model missing. Set HF_TOKEN and run: .\scripts\fetch-bundled-gemma-model.ps1`n$($_.Exception.Message)"
        }
        Write-Warning "Could not refresh bundled model (using existing file): $($_.Exception.Message)"
    }
} elseif (-not (Test-Path $bundledModel)) {
    throw "Bundled model missing at $bundledModel. Run .\scripts\fetch-bundled-gemma-model.ps1 first."
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

$githubRepo = if ($env:GITHUB_REPO) { $env:GITHUB_REPO } else { "piterdrori/CoffeeAi" }
$tag = "v$version"
$downloadUrl = "https://github.com/$githubRepo/releases/download/$tag/personal-edge-ai.apk"

Write-Host ""
Write-Host "==> Publishing APK to GitHub Releases ($githubRepo)..." -ForegroundColor Cyan
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    Write-Warning "GitHub CLI (gh) not found — skipping cloud upload. Install: winget install GitHub.cli"
} else {
    $releaseNotes = "CoffeeAI $version — unrestricted LLM (backend-controlled), fixes 2nd Let's Talk stuck on Thinking, and a more natural voice."
    $view = & gh release view $tag --repo $githubRepo 2>&1
    if ($LASTEXITCODE -ne 0) {
        & gh release create $tag --repo $githubRepo --title "CoffeeAI $version" --notes $releaseNotes $ApkDest
    } else {
        & gh release upload $tag --repo $githubRepo $ApkDest --clobber
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "GitHub release upload failed. Local APK is still available for WiFi download."
    } else {
        Write-Host "  GitHub: $downloadUrl" -ForegroundColor Green
    }
}

$appVersionPath = Join-Path $Root "backend\data\app_version.json"
$versionCode = 1
if (Test-Path $buildGradle) {
    $codeMatch = Select-String -Path $buildGradle -Pattern "versionCode\s*=\s*(\d+)" | Select-Object -First 1
    if ($codeMatch) { $versionCode = [int]$codeMatch.Matches[0].Groups[1].Value }
}
$appMeta = @{
    version        = $version
    version_code   = $versionCode
    updated_at     = (Get-Date).ToUniversalTime().ToString("o")
    apk_filename   = "personal-edge-ai.apk"
    apk_size_bytes = $size
    download_url   = $downloadUrl
    notes          = "CoffeeAI v$version - free LLM via backend, 2nd voice session fix, natural TTS voice"
}
$appMeta | ConvertTo-Json | Set-Content -Path $appVersionPath -Encoding UTF8

Write-Host ""
Write-Host "Published successfully!" -ForegroundColor Green
Write-Host "  APK:  $ApkDest ($([math]::Round($size / 1MB, 2)) MB)"
Write-Host "  Meta: $MetaDest"
Write-Host "  Cloud: $downloadUrl"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Deploy backend:  npx vercel --prod"
Write-Host "  2. Download page:   https://personal-edge-ai.vercel.app"
Write-Host "  3. Local WiFi:      docker compose up -d  ->  http://YOUR_PC_IP:8080/download/apk"
