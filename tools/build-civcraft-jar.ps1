$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Building CivCraft test jar from legacy compiled classes..."
mvn -pl civcraft clean package

Write-Host ""
Write-Host "Built jar:"
$BuiltJar = Get-ChildItem -Path ".\civcraft\target" -Filter "civcraft-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $BuiltJar) {
    throw "Build finished but no civcraft-*.jar was found in civcraft\target"
}
Write-Host $BuiltJar.FullName

Write-Host ""
& .\tools\inspect-built-jar.ps1 -JarPath $BuiltJar.FullName
