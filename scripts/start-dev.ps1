param(
  [int]$FrontendPort = 5180,
  [int]$BackendPort = 12400
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $root 'vue-code'
$jar = Get-ChildItem -Path (Join-Path $root 'target') -Filter 'XianYuSellerConsole-*.jar' -File |
  Where-Object { $_.Name -notlike '*.original' } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

# 探测可用的 JDK（优先项目 JDK，其次 JAVA_HOME，最后回退 Android Studio JBR）
$javaHome = $env:JAVA_HOME
$projectJdk = Join-Path $env:USERPROFILE '.jdks\jdk-21.0.12+8'
if (Test-Path (Join-Path $projectJdk 'bin\java.exe')) {
  $javaHome = $projectJdk
}

if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
  $javaHome = 'C:\Program Files\Android\Android Studio\jbr'
}

if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
  throw 'No usable Java runtime found. Set JAVA_HOME to a JDK installation.'
}

if (-not $jar) {
  throw 'Backend jar is missing. Run .\mvnw.cmd -B -ntp -DskipTests package first.'
}

function Assert-PortAvailable([int]$port, [string]$name) {
  $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if ($listener) {
    throw "$name port $port is already in use by PID $($listener[0].OwningProcess). Choose another port explicitly."
  }
}

Assert-PortAvailable $FrontendPort 'Frontend'
Assert-PortAvailable $BackendPort 'Backend'

$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$backendOut = Join-Path $logDir 'backend-dev.out.log'
$backendErr = Join-Path $logDir 'backend-dev.err.log'
$frontendOut = Join-Path $logDir 'frontend-dev.out.log'
$frontendErr = Join-Path $logDir 'frontend-dev.err.log'

# 启动后端（后台运行，日志重定向到文件）
$backendArgs = '--enable-native-access=ALL-UNNAMED -Dserver.port=' + $BackendPort + ' -jar ' + $jar

$backend = Start-Process -FilePath (Join-Path $javaHome 'bin\java.exe') `
  -ArgumentList $backendArgs `
  -WorkingDirectory $root `
  -RedirectStandardOutput $backendOut `
  -RedirectStandardError $backendErr `
  -WindowStyle Hidden `
  -PassThru

Write-Output "Backend PID: $($backend.Id) -> http://127.0.0.1:$BackendPort"

# 等待后端完成初始化（最多 40 秒），失败则抛出日志内容
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline -and -not $backend.HasExited) {
  $listener = Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue
  if ($listener) { break }
  Start-Sleep -Milliseconds 500
}

if ($backend.HasExited) {
  $errText = (Get-Content $backendErr -Raw -ErrorAction SilentlyContinue)
  $outText = (Get-Content $backendOut -Raw -ErrorAction SilentlyContinue)
  throw "Backend exited early with code $($backend.ExitCode).`n[err] $errText`n[out] $outText"
}

if (-not (Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue)) {
  throw "Backend started but port $BackendPort is not listening yet. Check $backendOut / $backendErr"
}

Write-Output "Backend is ready on http://127.0.0.1:$BackendPort (logs: $backendOut)"

# 前端前台运行（输出直接显示在终端），脚本保持运行；
# 前端退出（Ctrl+C / 停止命令）时兜底停掉后端，避免残留进程。
try {
  Push-Location $frontendDir
  $env:VITE_API_PROXY_TARGET = "http://127.0.0.1:$BackendPort"
  node "node_modules\vite\bin\vite.js" --host 127.0.0.1 --port $FrontendPort --strictPort
  Pop-Location
} finally {
  Remove-Item Env:VITE_API_PROXY_TARGET -ErrorAction SilentlyContinue
  if ($backend -and -not $backend.HasExited) {
    Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
  }
}
