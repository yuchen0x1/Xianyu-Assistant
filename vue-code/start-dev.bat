@echo off
echo ========================================
echo 闲鱼自动化管理系统 - Vue3 前端
echo ========================================
echo.
echo 正在启动开发服务器...
echo.
cd /d %~dp0
if not exist node_modules\vite\bin\vite.js (
  echo 未检测到前端依赖，正在按 package-lock.json 安装...
  call npm ci --no-audit --no-fund
  if errorlevel 1 (
    echo 前端依赖安装失败，请检查 Node.js 版本和网络连接。
    exit /b 1
  )
)
npm run dev
