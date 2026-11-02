@echo off
setlocal
set "PORT=%~1"
if "%PORT%"=="" set "PORT=12400"
where java >nul 2>nul
if errorlevel 1 (
  echo 未找到 Java，请安装 JDK 21 或设置 JAVA_HOME。
  exit /b 1
)
for %%F in ("%~dp0XianYuSellerConsole-*.jar") do (
  echo 正在启动闲鱼卖家运营控制台：http://127.0.0.1:%PORT%
  java -Dserver.port=%PORT% -jar "%%~fF"
  exit /b %errorlevel%
)
echo 找不到 XianYuSellerConsole-*.jar，请确认脚本与 JAR 位于同一目录。
exit /b 1
