# setup-jdk.ps1 — Install JDK 17 (Temurin) via winget and configure environment
#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"
$JDK_VERSION = "17"
$PACKAGE_ID  = "EclipseAdoptium.Temurin.$JDK_VERSION.JDK"

Write-Host "=== ai-bookkeeper: JDK $JDK_VERSION Setup ===" -ForegroundColor Cyan

# Check if JDK 17+ is already available
try {
    $ver = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
    if ([int]$ver -ge $JDK_VERSION) {
        Write-Host "JDK $ver already installed. No action needed." -ForegroundColor Green
        exit 0
    }
    Write-Host "Found JDK $ver, but $JDK_VERSION+ is required." -ForegroundColor Yellow
} catch {
    Write-Host "No JDK found on PATH." -ForegroundColor Yellow
}

# Install via winget
Write-Host "Installing $PACKAGE_ID via winget..." -ForegroundColor Cyan
winget install --id $PACKAGE_ID --accept-source-agreements --accept-package-agreements

# Refresh PATH in current session
$machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
$userPath    = [Environment]::GetEnvironmentVariable("Path", "User")
$env:PATH    = "$machinePath;$userPath"

# Also pick up JAVA_HOME set by installer
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
}

# Verify
$verCheck = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
if ([int]$verCheck -ge $JDK_VERSION) {
    Write-Host "SUCCESS: JDK $verCheck installed. JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Green
} else {
    Write-Host "ERROR: JDK installation could not be verified. Please install manually." -ForegroundColor Red
    Write-Host "  Download: https://adoptium.net/temurin/releases/?version=$JDK_VERSION" -ForegroundColor Yellow
    exit 1
}
