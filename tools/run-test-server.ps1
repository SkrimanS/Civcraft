param(
    [Parameter(Mandatory = $false)]
    [string]$ServerDir = "server-test",

    [Parameter(Mandatory = $false)]
    [int]$MemoryMb = 2048,

    [Parameter(Mandatory = $false)]
    [switch]$SkipEnvironmentCheck
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ServerPath = Join-Path $Root $ServerDir
$ServerJar = Join-Path $ServerPath "server.jar"

if (-not $SkipEnvironmentCheck) {
    & (Join-Path $Root "tools\check-test-environment.ps1") -ServerDir $ServerDir
}

if (-not (Test-Path $ServerJar)) {
    throw "Missing server jar: $ServerJar. Put a Spigot/Paper 1.12.2 jar there first."
}

$JavaCommand = Get-Command "java.exe" -ErrorAction Stop
$JavaArgs = @(
    "-Xms${MemoryMb}M",
    "-Xmx${MemoryMb}M",
    "-jar",
    "server.jar",
    "nogui"
)

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
    Write-Host "Java: $($JavaCommand.Source)"
    Write-Host "Memory: ${MemoryMb} MB"
    Write-Host "Log transcript: $TranscriptPath"
    & $JavaCommand.Source @JavaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Server process exited with code $LASTEXITCODE"
    }
} finally {
    Stop-Transcript | Out-Null
    Write-Host "Console transcript saved to: $TranscriptPath"
    Write-Host "Analyze it with: .\tools\analyze-test-log.ps1 -LogPath `"$TranscriptPath`""
}
