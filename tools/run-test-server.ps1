param(
    [Parameter(Mandatory = $false)]
    [string]$ServerDir = "server-test",

    [Parameter(Mandatory = $false)]
    [int]$MemoryMb = 2048
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ServerPath = Join-Path $Root $ServerDir
$ServerJar = Join-Path $ServerPath "server.jar"

if (-not (Test-Path $ServerJar)) {
    throw "Missing server jar: $ServerJar. Put a Spigot/Paper 1.12.2 jar there first."
}

Set-Location $ServerPath

$LogDir = Join-Path $ServerPath "logs"
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir | Out-Null
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$TranscriptPath = Join-Path $LogDir "console-$Stamp.txt"

Start-Transcript -Path $TranscriptPath -Force | Out-Null
try {
    Write-Host "Starting CivCraft 1.12.2 test server..."
    Write-Host "Log transcript: $TranscriptPath"
    java -Xms${MemoryMb}M -Xmx${MemoryMb}M -jar server.jar nogui
} finally {
    Stop-Transcript | Out-Null
    Write-Host "Console transcript saved to: $TranscriptPath"
}
