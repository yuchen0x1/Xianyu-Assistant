$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

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

Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', 'test') $root
Invoke-Checked 'npm.cmd' @('ci') (Join-Path $root 'vue-code')
Invoke-Checked 'npm.cmd' @('test') (Join-Path $root 'vue-code')
Invoke-Checked 'npm.cmd' @('run', 'lint:check') (Join-Path $root 'vue-code')
Invoke-Checked 'npm.cmd' @('run', 'type-check') (Join-Path $root 'vue-code')
Invoke-Checked 'npm.cmd' @('run', 'build') (Join-Path $root 'vue-code')

Write-Host "`nWeb application checks passed." -ForegroundColor Green
