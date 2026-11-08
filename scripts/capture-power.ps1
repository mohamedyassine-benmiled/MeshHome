param(
    [ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$Tag = 'snapshot',
    [string]$Serial = '',
    [string]$Adb = ''
)
$ErrorActionPreference = 'Stop'
if (!$Adb) {
    $installed = Get-Command adb -ErrorAction SilentlyContinue
    if ($installed) { $Adb = $installed.Source }
    else { $Adb = Join-Path $env:LOCALAPPDATA 'Temp\opencode\meshhome-sdk\platform-tools\adb.exe' }
}
if (!(Test-Path $Adb)) { throw 'ADB not found. Pass -Adb with the platform-tools executable path.' }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
function Read-Adb([string[]]$AdbArguments) {
    $result = & $Adb @deviceArgs @AdbArguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB read failed. Check device connection/authorization and select -Serial if needed.' }
    return $result
}
if ((Read-Adb -AdbArguments @('get-state')) -ne 'device') { throw 'Connect and authorize a test phone first; select -Serial if more than one is connected.' }
$root = Split-Path $PSScriptRoot -Parent
$folder = Join-Path $root ('dist\power\' + $Tag + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
if (Test-Path $folder) { throw 'Snapshot folder already exists. Choose another tag.' }
New-Item -ItemType Directory $folder -Force | Out-Null
$package = 'com.mybm.meshhome'
Read-Adb -AdbArguments @('shell', 'dumpsys', 'batterystats', $package) | Out-File (Join-Path $folder 'batterystats.txt') -Encoding utf8
Read-Adb -AdbArguments @('shell', 'dumpsys', 'meminfo', $package) | Out-File (Join-Path $folder 'meminfo.txt') -Encoding utf8
Read-Adb -AdbArguments @('shell', 'dumpsys', 'battery') | Out-File (Join-Path $folder 'battery.txt') -Encoding utf8
Read-Adb -AdbArguments @('shell', 'dumpsys', 'package', $package) | Select-String 'versionCode=|versionName=' |
    Out-File (Join-Path $folder 'version.txt') -Encoding utf8
[pscustomobject]@{ tag = $Tag; capturedAtUtc = (Get-Date).ToUniversalTime().ToString('o'); package = $package; readOnly = $true } |
    ConvertTo-Json | Out-File (Join-Path $folder 'snapshot.json') -Encoding utf8
Write-Output "Read-only power snapshot saved to $folder"
