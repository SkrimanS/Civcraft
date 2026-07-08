param(
    [Parameter(Mandatory = $false)]
    [string]$ServerDir = "server-test"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ServerPath = Join-Path $Root $ServerDir
$PluginsPath = Join-Path $ServerPath "plugins"
$CivCraftConfigPath = Join-Path $PluginsPath "CivCraft\config.yml"
$ServerJar = Join-Path $ServerPath "server.jar"
$CivCraftJar = Join-Path $PluginsPath "CivCraft.jar"

$RecommendedPlugins = @(
    "CustomMobs.jar",
    "Vault.jar",
    "WorldBorder.jar",
    "WorldEdit.jar",
    "HeroChat.jar",
    "TitleAPI.jar",
    "TagAPI.jar",
    "NoCheatPlus.jar"
)

$Errors = @()
$Warnings = @()

function Test-FileRequired([string]$Path, [string]$Name) {
    if (Test-Path $Path) {
        Write-Host "OK      $Name"
    } else {
        Write-Host "MISSING $Name"
        $script:Errors += $Name
    }
}

function Test-FileRecommended([string]$Path, [string]$Name) {
    if (Test-Path $Path) {
        Write-Host "OK      $Name"
    } else {
        Write-Host "WARN    $Name"
        $script:Warnings += $Name
    }
}

Write-Host "Checking CivCraft 1.12.2 local test environment"
Write-Host "Root:       $Root"
Write-Host "ServerDir:  $ServerPath"
Write-Host ""

if (-not (Test-Path $ServerPath)) {
    Write-Host "MISSING server-test directory"
    Write-Host "Run: .\tools\prepare-test-server.ps1 -ServerJar C:\path\to\server.jar"
    exit 1
}

Test-FileRequired $ServerJar "server-test\server.jar"
Test-FileRequired $CivCraftJar "server-test\plugins\CivCraft.jar"
Test-FileRequired $CivCraftConfigPath "server-test\plugins\CivCraft\config.yml"

Write-Host ""
Write-Host "Recommended plugin jars:"
foreach ($Plugin in $RecommendedPlugins) {
    Test-FileRecommended (Join-Path $PluginsPath $Plugin) "server-test\plugins\$Plugin"
}

Write-Host ""
Write-Host "Checking local MariaDB port 3306..."
$TcpClient = New-Object System.Net.Sockets.TcpClient
try {
    $Async = $TcpClient.BeginConnect("127.0.0.1", 3306, $null, $null)
    $Connected = $Async.AsyncWaitHandle.WaitOne(1000, $false)
    if ($Connected -and $TcpClient.Connected) {
        Write-Host "OK      MariaDB port 3306 is reachable"
    } else {
        Write-Host "WARN    MariaDB port 3306 is not reachable"
        $Warnings += "MariaDB port 3306"
    }
} finally {
    $TcpClient.Close()
}

Write-Host ""
if ($Errors.Count -gt 0) {
    Write-Host "Environment has required missing item(s):"
    $Errors | ForEach-Object { Write-Host "- $_" }
    exit 1
}

if ($Warnings.Count -gt 0) {
    Write-Host "Environment has warning(s), but test can still run:"
    $Warnings | ForEach-Object { Write-Host "- $_" }
    exit 0
}

Write-Host "Environment looks ready."
