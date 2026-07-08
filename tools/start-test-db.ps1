$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Starting CivCraft local MariaDB test database..."
docker compose -f docker-compose.civcraft-test.yml up -d

Write-Host ""
Write-Host "Database settings for CivCraft config.yml:"
Write-Host "  mysql.hostname: localhost"
Write-Host "  mysql.port: 3306"
Write-Host "  mysql.database: game"
Write-Host "  mysql.username: civcraft"
Write-Host "  mysql.password: civcraft"
Write-Host ""
Write-Host "  global_database.hostname: localhost"
Write-Host "  global_database.port: 3306"
Write-Host "  global_database.database: global"
Write-Host "  global_database.username: civcraft"
Write-Host "  global_database.password: civcraft"
