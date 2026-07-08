$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Building CivCraft test jar from legacy compiled classes..."
mvn -pl civcraft clean package

Write-Host ""
Write-Host "Built jar:"
Get-ChildItem -Path ".\civcraft\target" -Filter "civcraft-*.jar" | Select-Object -ExpandProperty FullName
