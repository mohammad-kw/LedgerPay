# ============================================================================
# LedgerPay - backend launcher
# ============================================================================
# Loads secrets from backend\.env (if present) into this PowerShell session's
# environment, then starts the Spring Boot backend. This replaces having to
# type `$env:DB_USERNAME = ...; $env:RAZORPAY_KEY_SECRET = ...` by hand every
# time.
#
# USAGE (from the repo root c:\dev\LedgerPay):
#   .\run-backend.ps1
#
# SECURITY: backend\.env is gitignored, so nothing loaded here is ever
# committed. This script only READS that file - it never prints the values.
# ============================================================================

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile  = Join-Path $repoRoot "backend\.env"
$pomFile  = Join-Path $repoRoot "backend\pom.xml"

if (Test-Path $envFile) {
    Write-Host "Loading secrets from backend\.env ..." -ForegroundColor Cyan
    $loaded = 0
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        # Skip blank lines and comments (# ...)
        if ($line -eq "" -or $line.StartsWith("#")) { return }
        # Split on the FIRST '=' only, so values containing '=' survive.
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { return }
        $name  = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        # Strip optional surrounding quotes.
        if ($value.Length -ge 2 -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
             ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$name" -Value $value
        $loaded++
    }
    # Note: we print the COUNT only, never the names or values.
    Write-Host "Loaded $loaded environment variable(s)." -ForegroundColor Green
} else {
    Write-Host "No backend\.env found - using defaults from application.properties." -ForegroundColor Yellow
    Write-Host "Tip: copy backend\.env.example to backend\.env and fill in your keys." -ForegroundColor Yellow
}

Write-Host "Starting backend..." -ForegroundColor Cyan
mvn -f $pomFile spring-boot:run
