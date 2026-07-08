param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,

    [Parameter(Mandatory = $false)]
    [string]$ServerDir = "server-test",

    [Parameter(Mandatory = $false)]
    [int]$MemoryMb = 2048
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "=== CivCraft 1.12.2 full local smoke test ==="
Write-Host ""

Write-Host "[1/5] Building CivCraft jar"
& .\tools\build-civcraft-jar.ps1

Write-Host ""
Write-Host "[2/5] Starting local MariaDB"
& .\tools\start-test-db.ps1

Write-Host ""
Write-Host "[3/5] Preparing test server"
& .\tools\prepare-test-server.ps1 -ServerJar $ServerJar -ServerDir $ServerDir

Write-Host ""
Write-Host "[4/5] Checking test environment"
& .\tools\check-test-environment.ps1 -ServerDir $ServerDir

Write-Host ""
Write-Host "[5/5] Starting test server"
& .\tools\run-test-server.ps1 -ServerDir $ServerDir -MemoryMb $MemoryMb -SkipEnvironmentCheck
