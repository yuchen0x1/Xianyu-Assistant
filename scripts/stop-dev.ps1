param(
  [int]$FrontendPort = 5180,
  [int]$BackendPort = 12400
)

$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot

Get-CimInstance Win32_Process | ForEach-Object {
  $commandLine = [string]$_.CommandLine
  $isBackend = $commandLine -like '*XianYuSellerConsole-*.jar*' -and $commandLine -like "*$root*"
  if ($isBackend) {
    Stop-Process -Id $_.ProcessId -Force
  }
}

$frontendListener = Get-NetTCPConnection -LocalPort $FrontendPort -State Listen
if ($frontendListener) {
  $frontendPid = $frontendListener[0].OwningProcess
  $frontendProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $frontendPid"
  $frontendCommandLine = [string]$frontendProcess.CommandLine
  if ($frontendCommandLine -like "*vite\bin\vite.js*") {
    Stop-Process -Id $frontendPid -Force
  }
}
