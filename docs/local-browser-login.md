# 本机浏览器登录接管

当前线上服务器无法稳定完成闲鱼账号登录或风控验证时，使用本机真实浏览器完成登录，再把已确认的会话凭据交给后台。这个流程不尝试破解或绕过验证码。

## 使用步骤

1. 在后台停止目标账号的 WebSocket 连接和自动任务。
2. 在项目根目录打开 PowerShell，执行：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\export-goofish-session.ps1 -AccountId 1
   ```

   把 `1` 换成实际的闲鱼账号 ID。每个账号都会使用独立的 Chrome profile。

3. 在脚本打开的浏览器中完成扫码登录、手机号验证或滑块验证，并确认 `https://www.goofish.com/im` 能正常打开消息页。
4. 回到 PowerShell 按 Enter。脚本只读取 Goofish/淘宝/天猫相关 Cookie，校验 `unb` 和 `_m_h5_tk`，然后复制到剪贴板，不会把 Cookie 打印到终端。
5. 回到后台的“手动更新 Cookie”对话框粘贴并提交。
6. 粘贴提交后立即清空系统剪贴板。
7. 只等待一次真实 Token 和 WebSocket ACK 探测。成功才显示在线；失败就让账号保持暂停状态，等待平台冷却或重新在本机浏览器验证。

## 参数

- `-AccountId <数字>`：必填，后台账号 ID。
- `-DebugPort <端口>`：默认 `9222`。如果端口被占用，换成其他端口。
- `-ReuseExistingChrome`：复用已经用相同调试端口启动的浏览器。
- `-OutputPath <路径>`：可选，将 Cookie 写入文件。默认不落盘；写入后请立即删除。

## 安全边界

- 不要在聊天、截图、Git 或工单中发送 Cookie、Token 或浏览器 profile。
- 不要同时让两个账号共用同一个 profile。
- 不要在验证失败后连续刷新 Token 或反复打开验证页。
- 这个流程不能保证平台一定放行，只是让人工验证和登录设备保持在同一个本机浏览器环境中。
