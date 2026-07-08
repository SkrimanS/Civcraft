$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Stopping CivCraft local MariaDB test database..."
docker compose -f docker-compose.civcraft-test.yml down
