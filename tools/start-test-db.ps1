$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Checking Docker Desktop..."
$DockerCommand = Get-Command "docker.exe" -ErrorAction SilentlyContinue
if ($null -eq $DockerCommand) {
    throw "docker.exe not found. Install Docker Desktop or start it if it is installed."
}

& $DockerCommand.Source info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker daemon is not running. Start Docker Desktop and wait until it says Docker is running, then retry."
}

Write-Host "Starting CivCraft local MariaDB test database..."
& $DockerCommand.Source compose -f docker-compose.civcraft-test.yml up -d
if ($LASTEXITCODE -ne 0) {
    throw "docker compose failed. Check Docker Desktop and docker-compose.civcraft-test.yml."
}

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
