#Requires -Version 5.1
<#
.SYNOPSIS
  Download Gemma 3 1B IT LiteRT model into APK assets for built-in inference.
.DESCRIPTION
  Fetches gemma3-1b-it-int4.litertlm (~620 MB) from Hugging Face into
  android/app/src/main/assets/models/ so it ships inside the APK.

  Requires HF_TOKEN env var (read token) after accepting the Gemma license at:
  https://huggingface.co/litert-community/Gemma3-1B-IT
.EXAMPLE
  $env:HF_TOKEN = "hf_..."
  .\scripts\fetch-bundled-gemma-model.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AssetsDir = Join-Path $Root "android\app\src\main\assets\models"
$FileName = "gemma3-1b-it-int4.litertlm"
$Dest = Join-Path $AssetsDir $FileName
$Url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/$FileName"
$MinBytes = 558000000

if (-not (Test-Path $AssetsDir)) {
    New-Item -ItemType Directory -Path $AssetsDir -Force | Out-Null
}

if ((Test-Path $Dest) -and ((Get-Item $Dest).Length -ge $MinBytes)) {
    Write-Host "Bundled model already present: $Dest" -ForegroundColor Green
    Write-Host "Size: $([math]::Round((Get-Item $Dest).Length / 1MB, 1)) MB"
    exit 0
}

$token = $env:HF_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    throw @"
HF_TOKEN is not set.

1. Accept the Gemma license: https://huggingface.co/litert-community/Gemma3-1B-IT
2. Create a read token: https://huggingface.co/settings/tokens
3. Run:
   `$env:HF_TOKEN = 'hf_...'
   .\scripts\fetch-bundled-gemma-model.ps1
"@
}

Write-Host "Downloading $FileName to assets..." -ForegroundColor Cyan
Write-Host "Destination: $Dest"

$headers = @{
    Authorization = "Bearer $token"
    "User-Agent"  = "PersonalEdgeAI/1.0"
}

Invoke-WebRequest -Uri $Url -Headers $headers -OutFile $Dest -UseBasicParsing

if (-not (Test-Path $Dest)) {
    throw "Download failed: file not created"
}

$size = (Get-Item $Dest).Length
if ($size -lt $MinBytes) {
    Remove-Item $Dest -Force
    throw "Download looks incomplete ($size bytes). Check HF_TOKEN and Gemma license acceptance."
}

$previewBytes = New-Object byte[] 32
$stream = [System.IO.File]::OpenRead($Dest)
try {
    $read = $stream.Read($previewBytes, 0, 32)
} finally {
    $stream.Close()
}
$preview = [System.Text.Encoding]::ASCII.GetString($previewBytes, 0, $read)
if ($preview.TrimStart().StartsWith("<!") -or $preview.TrimStart().StartsWith("{")) {
    Remove-Item $Dest -Force
    throw "Download returned HTML/JSON instead of model binary. Check HF_TOKEN."
}

Write-Host "Bundled model ready: $([math]::Round($size / 1MB, 1)) MB" -ForegroundColor Green
