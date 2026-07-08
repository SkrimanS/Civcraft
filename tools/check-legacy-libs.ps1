$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$LibDir = Join-Path $Root "civcraft\lib"

$Expected = @(
    "dhutils.jar",
    "HeroChat.jar",
    "Vault.jar",
    "VanishNoPacket.jar",
    "WorldBorder.jar",
    "NoCheatPlus.jar",
    "TitleAPI.jar",
    "TagAPI.jar",
    "CustomMobs-4.0-INDEV.jar",
    "craftbukkit-1.12.2.jar"
)

Write-Host "Checking CivCraft legacy library directory: $LibDir"
Write-Host ""

if (-not (Test-Path $LibDir)) {
    Write-Host "Creating missing directory: $LibDir"
    New-Item -ItemType Directory -Path $LibDir | Out-Null
}

$Missing = @()
foreach ($Jar in $Expected) {
    $Path = Join-Path $LibDir $Jar
    if (Test-Path $Path) {
        Write-Host "OK      $Jar"
    } else {
        Write-Host "MISSING $Jar"
        $Missing += $Jar
    }
}

Write-Host ""
if ($Missing.Count -gt 0) {
    Write-Host "Missing $($Missing.Count) required local jar(s)."
    Write-Host "Put these files into civcraft\lib with exactly these names, then rerun this script."
    Write-Host ""
    Write-Host "These names are normalized from the old Eclipse .classpath. For example:"
    Write-Host "- 'Vault v1.5.6.jar' should be copied/renamed to 'Vault.jar'"
    Write-Host "- 'HeroChat (2).jar' should be copied/renamed to 'HeroChat.jar'"
    Write-Host "- 'WorldBorder v1.8.5.jar' should be copied/renamed to 'WorldBorder.jar'"
    Write-Host "- 'NoCheatPlus 2016-05-21.jar' should be copied/renamed to 'NoCheatPlus.jar'"
    exit 1
}

Write-Host "All legacy jars are present."
