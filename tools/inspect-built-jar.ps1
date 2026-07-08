param(
    [Parameter(Mandatory = $false)]
    [string]$JarPath = ""
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$TargetDir = Join-Path $Root "civcraft\target"

if ($JarPath -eq "") {
    $Latest = Get-ChildItem -Path $TargetDir -Filter "civcraft-*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $Latest) {
        throw "No civcraft-*.jar found in $TargetDir. Build first."
    }
    $JarPath = $Latest.FullName
}

$ResolvedJar = Resolve-Path $JarPath
Write-Host "Inspecting jar: $ResolvedJar"
Write-Host ""

$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("civcraft-jar-inspect-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $TempDir | Out-Null

try {
    Push-Location $TempDir
    jar xf $ResolvedJar plugin.yml
    Pop-Location

    $PluginYml = Join-Path $TempDir "plugin.yml"
    if (-not (Test-Path $PluginYml)) {
        throw "plugin.yml not found inside jar. Bukkit will not load this plugin."
    }

    Write-Host "plugin.yml:"
    Get-Content $PluginYml | ForEach-Object { Write-Host "  $_" }

    Write-Host ""
    Write-Host "Checking main class..."
    $MainClassEntry = "com/avrgaming/civcraft/main/CivCraft.class"
    $ClassExists = (jar tf $ResolvedJar | Select-String -SimpleMatch $MainClassEntry)
    if ($ClassExists) {
        Write-Host "OK: $MainClassEntry"
    } else {
        throw "Missing main class: $MainClassEntry"
    }

    Write-Host ""
    Write-Host "Jar inspection passed."
} finally {
    if (Test-Path $TempDir) {
        Remove-Item -Recurse -Force $TempDir
    }
}
