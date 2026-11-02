param(
  [switch]$SkipAndroidBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$originalNoProxy = $env:NO_PROXY
$originalLowerNoProxy = $env:no_proxy
$mappedDrive = $null

function Invoke-Checked([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory) {
  Write-Host "`n> $FilePath $($Arguments -join ' ')" -ForegroundColor Cyan
  Push-Location $WorkingDirectory
  try {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
      throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
  } finally {
    Pop-Location
  }
}

try {
  Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', 'test') $root
  Invoke-Checked 'npm.cmd' @('ci') (Join-Path $root 'vue-code')
  Invoke-Checked 'npm.cmd' @('test') (Join-Path $root 'vue-code')
  Invoke-Checked 'npm.cmd' @('run', 'type-check') (Join-Path $root 'vue-code')
  Invoke-Checked 'npm.cmd' @('run', 'build') (Join-Path $root 'vue-code')

  $mobileRoot = Join-Path $root 'mobile'
  $driveCandidates = @('Z', 'Y', 'X', 'W')
  if ($root -match '[^\u0000-\u007F]') {
    $drive = $driveCandidates | Where-Object { -not (Test-Path "$_`:\") } | Select-Object -First 1
    if (-not $drive) {
      throw 'No temporary drive letter is available for Flutter Windows path validation.'
    }
    $mappedDrive = "$drive`:"
    subst $mappedDrive $root
    if ($LASTEXITCODE -ne 0) {
      throw "Could not map $root to $mappedDrive."
    }
    $mobileRoot = Join-Path "$mappedDrive\" 'mobile'
  }

  Invoke-Checked 'flutter.bat' @('pub', 'get') $mobileRoot
  Invoke-Checked 'flutter.bat' @('analyze', '--no-pub') $mobileRoot
  $env:NO_PROXY = '127.0.0.1,localhost'
  $env:no_proxy = $env:NO_PROXY
  Invoke-Checked 'flutter.bat' @('test', '--no-pub') $mobileRoot
  if (-not $SkipAndroidBuild) {
    Invoke-Checked 'flutter.bat' @('build', 'apk', '--debug', '--no-pub', '--android-skip-build-dependency-validation') $mobileRoot
  }

  Write-Host "`nProject checks passed." -ForegroundColor Green
} finally {
  if ($mappedDrive) {
    subst $mappedDrive /d | Out-Null
  }
  if ($null -eq $originalNoProxy) { Remove-Item Env:NO_PROXY -ErrorAction SilentlyContinue } else { $env:NO_PROXY = $originalNoProxy }
  if ($null -eq $originalLowerNoProxy) { Remove-Item Env:no_proxy -ErrorAction SilentlyContinue } else { $env:no_proxy = $originalLowerNoProxy }
}
