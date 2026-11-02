param(
  [int]$Port = 12400
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem -Path $root -Filter 'XianYuSellerConsole-*.jar' -File | Select-Object -First 1

if (-not $jar) {
  throw '找不到 XianYuSellerConsole-*.jar，请确认脚本与 JAR 位于同一目录。'
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
  throw '未找到 Java。请安装 JDK 21 或设置 JAVA_HOME。'
}

Write-Host "正在启动闲鱼卖家运营控制台：http://127.0.0.1:$Port"
Write-Host '按 Ctrl+C 停止服务。'
& $java.Source '-Dserver.port=' + $Port '-jar' $jar.FullName
