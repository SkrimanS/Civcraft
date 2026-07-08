param(
    [Parameter(Mandatory = $false)]
    [string]$ServerJar = "",

    [Parameter(Mandatory = $false)]
    [string]$ServerDir = "server-test"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ServerPath = Join-Path $Root $ServerDir
$PluginsPath = Join-Path $ServerPath "plugins"
$CivCraftPluginPath = Join-Path $PluginsPath "CivCraft"
$CivTargetPath = Join-Path $Root "civcraft\target"
$ConfigTemplatePath = Join-Path $Root "tools\templates\civcraft-test-config.yml"

Set-Location $Root

if (-not (Test-Path $ServerPath)) {
    New-Item -ItemType Directory -Path $ServerPath | Out-Null
}
if (-not (Test-Path $PluginsPath)) {
    New-Item -ItemType Directory -Path $PluginsPath | Out-Null
}
if (-not (Test-Path $CivCraftPluginPath)) {
    New-Item -ItemType Directory -Path $CivCraftPluginPath | Out-Null
}

Set-Content -Path (Join-Path $ServerPath "eula.txt") -Value "eula=true" -Encoding UTF8

if (Test-Path $ConfigTemplatePath) {
    Copy-Item -Path $ConfigTemplatePath -Destination (Join-Path $CivCraftPluginPath "config.yml") -Force
    Write-Host "Wrote local CivCraft test config to plugins\CivCraft\config.yml"
}

$BuiltJar = Get-ChildItem -Path $CivTargetPath -Filter "civcraft-*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -ne $BuiltJar) {
    Copy-Item -Path $BuiltJar.FullName -Destination (Join-Path $PluginsPath "CivCraft.jar") -Force
    Write-Host "Copied CivCraft jar to plugins\CivCraft.jar"
} else {
    Write-Host "CivCraft jar not found in civcraft\target. Run .\tools\build-civcraft-jar.ps1 first."
}

if ($ServerJar -ne "") {
    $ResolvedServerJar = Resolve-Path $ServerJar
    Copy-Item -Path $ResolvedServerJar -Destination (Join-Path $ServerPath "server.jar") -Force
    Write-Host "Copied server jar to server-test\server.jar"
} else {
    Write-Host "No server jar supplied. Put your Spigot/Paper 1.12.2 jar at:"
    Write-Host "  $ServerPath\server.jar"
}

$Readme = @"
# CivCraft local 1.12.2 test server

Expected layout:

server-test/
  server.jar
  eula.txt
  plugins/
    CivCraft.jar
    CustomMobs.jar
    Vault.jar
    WorldBorder.jar
    WorldEdit.jar
    HeroChat.jar        optional at first
    TitleAPI.jar        optional at first
    TagAPI.jar          optional at first
    NoCheatPlus.jar     optional at first
    CivCraft/
      config.yml

Important:
- CivCraft plugin.yml currently has depend: [CustomMobs], so CustomMobs must exist or Bukkit will refuse to enable CivCraft.
- Start the local MariaDB test database before gameplay tests: ..\tools\start-test-db.ps1
- The generated CivCraft config uses local MariaDB databases: game and global, user civcraft, password civcraft.
- First run may still fail while generating data or loading dependencies; collect the full console log.

Start command:

powershell -ExecutionPolicy Bypass -File ..\tools\run-test-server.ps1
"@

Set-Content -Path (Join-Path $ServerPath "README.md") -Value $Readme -Encoding UTF8

Write-Host ""
Write-Host "Prepared test server folder: $ServerPath"
Write-Host "Next: add server.jar and dependency plugins, start DB, then run tools\run-test-server.ps1"
