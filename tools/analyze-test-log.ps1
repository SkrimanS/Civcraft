param(
    [Parameter(Mandatory = $false)]
    [string]$LogPath = ""
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$DefaultLogDir = Join-Path $Root "server-test\logs"

if ($LogPath -eq "") {
    $Latest = Get-ChildItem -Path $DefaultLogDir -Filter "console-*.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $Latest) {
        throw "No console-*.txt logs found in $DefaultLogDir"
    }
    $LogPath = $Latest.FullName
}

$ResolvedLog = Resolve-Path $LogPath
Write-Host "Analyzing log: $ResolvedLog"
Write-Host ""

$Patterns = @(
    "CivCraft",
    "CustomMobs",
    "Vault",
    "WorldEdit",
    "WorldBorder",
    "HeroChat",
    "TitleAPI",
    "TagAPI",
    "NoCheatPlus",
    "Exception",
    "Error",
    "SEVERE",
    "WARN",
    "Could not load",
    "Could not pass event",
    "NoClassDefFoundError",
    "ClassNotFoundException",
    "NoSuchMethodError",
    "NoSuchFieldError",
    "SQLException",
    "CommunicationsException",
    "Access denied",
    "Unknown database"
)

$Regex = ($Patterns | ForEach-Object { [Regex]::Escape($_) }) -join "|"

$Matches = Select-String -Path $ResolvedLog -Pattern $Regex -CaseSensitive:$false -Context 2,5

if ($Matches.Count -eq 0) {
    Write-Host "No obvious error patterns found."
    exit 0
}

foreach ($Match in $Matches) {
    Write-Host "--------------------------------------------------------------------------------"
    Write-Host "Line $($Match.LineNumber): $($Match.Line)"
    foreach ($Pre in $Match.Context.PreContext) {
        Write-Host "  $Pre"
    }
    foreach ($Post in $Match.Context.PostContext) {
        Write-Host "  $Post"
    }
}

Write-Host ""
Write-Host "Done. Send the full log if the summary is not enough."
