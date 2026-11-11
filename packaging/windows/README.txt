闲鱼卖家运营控制台电脑版

环境要求：JDK 21。双击 start-console.bat，或在 PowerShell 中运行：
  .\start-console.ps1

启动后访问：http://127.0.0.1:12400
如需让手机通过局域网访问，请在 Windows 防火墙放行 TCP 12400，并在手机端填写电脑局域网地址，例如：
  http://192.168.1.100:12400

数据库会保存在启动目录下的 dbdata，请勿删除该目录。

【浏览器兜底依赖（可选）】
扫码登录与 Cookie 兜底刷新依赖本机 Chromium。程序会按以下顺序自动查找：
  1. 系统已安装的 Google Chrome / Microsoft Edge
  2. PLAYWRIGHT_BROWSERS_PATH 指向的浏览器缓存目录
  3. 用户级 Playwright 缓存（%LOCALAPPDATA%\ms-playwright）

三者都没有时，扫码与手工刷新仍可用，只是无法自动续期 Cookie。
此时启动日志会打印「未找到可用的 Chromium」，安装方式任选其一：
  - 安装 Google Chrome；
  - 在任意目录执行 npx playwright install chromium；
  - 设置环境变量 PLAYWRIGHT_BROWSERS_PATH 指向已有的浏览器缓存目录。
