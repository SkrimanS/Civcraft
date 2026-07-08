$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

& .\tools\check-legacy-libs.ps1

Write-Host ""
Write-Host "Compiling CivCraft from source against the 1.12.2 port profile..."
mvn -pl civcraft -Psource-compile clean package

Write-Host ""
Write-Host "Built source jar:"
Get-ChildItem -Path ".\civcraft\target" -Filter "civcraft-*.jar" | Select-Object -ExpandProperty FullName
