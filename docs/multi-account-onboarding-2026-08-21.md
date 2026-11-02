# 多账号接入与并行验证记录（2026-08-21）

本文记录一次真实执行：用「本机独立浏览器 profile 登录 → Cookie 交后台 → 触发连接」的方式接入两个闲鱼账号，并验证两个账号可以并行在线、互不覆盖、消息链路正常。

## 结论

- 两个账号（`账号A`、`账号B`）接入后同时保持 WebSocket 在线，观察 3 分钟内无重连、无掉线。
- 数据库层面两个账号完全隔离：`unb`、设备 ID、Cookie、WebSocket Token 各自独立存储。
- 跨账号消息测试通过：账号 B 给账号 A 发消息后，账号 A 的连接实时收到同步包、解密并入库；账号 B 的连接正确识别该消息为自己发送并跳过自动回复。
- 「登录第二个账号覆盖第一个」是平台会话互斥 + 所有账号共用同一浏览器/设备环境导致的，不是本工具存储逻辑问题。使用独立 profile 后不会发生。

## 前置条件

- 后端已构建：`target/XianYuSellerConsole-3.3.17.jar`（先 `./mvnw.cmd -B -ntp -DskipTests package`）。
- 本机装有 Chrome 或 Edge。
- 后端启动后，通过登录接口/已有会话获取管理员 token 备用（`/api/login` 或复用 `sys_login_token` 表中未过期 token）。

## 账号接入步骤

每个账号固定使用一个独立 profile，禁止混用。

1. 启动后端（默认端口 12400）：

   ```powershell
   java --enable-native-access=ALL-UNNAMED -Dserver.port=12400 -jar target\XianYuSellerConsole-3.3.17.jar
   ```

2. 对每个账号执行本机登录导出脚本，`AccountId` 与后台账号 ID 保持一致（首次添加时该 ID 也决定 profile 目录名）：

   ```powershell
   # 账号 A
   powershell -ExecutionPolicy Bypass -File .\scripts\export-goofish-session.ps1 -AccountId 1 -DebugPort 9222 -OutputPath "$env:TEMP\xy-cookie-account-1.txt"

   # 账号 B（换独立 profile、独立调试端口）
   powershell -ExecutionPolicy Bypass -File .\scripts\export-goofish-session.ps1 -AccountId 2 -DebugPort 9223 -OutputPath "$env:TEMP\xy-cookie-account-2.txt"
   ```

3. 在弹出的专用 Chrome 窗口中完成扫码/手机验证，确认 `https://www.goofish.com/im` 消息页正常打开后回车。脚本会导出 Cookie 到剪贴板和指定文件（校验 `unb`、`_m_h5_tk`）。

4. 用管理 token 调用手动添加接口写入后端：

   ```powershell
   $body = @{ accountNote = '账号A'; cookie = <导出的Cookie> } | ConvertTo-Json -Compress
   Invoke-RestMethod -Uri 'http://127.0.0.1:12400/api/account/manualAdd' -Method Post `
     -Headers @{ Authorization = "Bearer <token>" } -ContentType 'application/json; charset=utf-8' -Body $body
   ```

5. 触发连接并核对状态：

   ```powershell
   Invoke-RestMethod -Uri 'http://127.0.0.1:12400/api/websocket/start' -Method Post `
     -Headers @{ Authorization = "Bearer <token>" } -ContentType 'application/json' `
     -Body (@{ xianyuAccountId = 1 } | ConvertTo-Json -Compress)
   Invoke-RestMethod -Uri 'http://127.0.0.1:12400/api/websocket/status' -Method Post `
     -Headers @{ Authorization = "Bearer <token>" } -ContentType 'application/json' `
     -Body (@{ xianyuAccountId = 1 } | ConvertTo-Json -Compress)
   ```

   期望：`connected=true`、`status=已连接`、`websocketTokenConfigured=true`；日志出现「WebSocket连接成功」「✅ Token已成功保存到数据库」「✅ 注册成功」。

## 并行在线验证

- 用状态接口轮询所有账号，确认同时为「已连接」且 `reconnecting=false`。
- 观察后端日志，确认两个账号各自的心跳任务运行（`心跳间隔15秒`），无「断开/掉线/FAIL」。
- 跨账号消息测试：从账号 B 的闲鱼 APP/网页给账号 A 的商品发一条消息，日志应出现：
  - 账号 A：`收到闲鱼原始消息: lwp=/s/sync` → 解密成功 → `ChatMessageReceivedEvent事件已发布` → `[SaveListener]消息保存成功`。
  - 账号 A 的 `[AutoReplyListener]` 会判断发送者与自己不同；若对应商品未开启自动回复，会记录 `商品未开启自动回复开关，跳过`，属于预期行为。
  - 账号 B：`senderUserId与ownUserId` 相同，`自己发送的消息，跳过自动回复`。

## 注意事项

- 绝对不要在两个账号之间共用同一个 profile；Cookie 刷新也必须对号入座（账号 A 用 `account-1`，账号 B 用 `account-2`）。
- 两个专用浏览器窗口可以保持打开（便于以后刷新 Cookie），也可以关闭——后台连接不依赖浏览器。
- 任一账号触发平台风控时，后台只暂停该账号并等待人工验证，不影响其他账号（`account_risk_event`、`account_automation_pause`）。
- 操作产生的临时 Cookie 文件、token 文件使用后立即删除；导出脚本已把 Cookie 复制到剪贴板，粘贴后清空剪贴板。
- 自动回复真正发出回复前，需要为对应商品开启 `xianyu_goods_config` 中的自动回复/关键词回复开关并配置关键词规则。

## 本次验证快照

- 账号 A：`id=1`，unb 前缀 `2200***`；账号 B：`id=2`，unb 前缀 `2222***`。
- 消息测试：账号 B → 账号 A 发送「你好」，两条 `/s/sync` 同步包同时到达两个连接，账号 A 侧消息入库成功，自动回复按配置门控跳过。
- 风控事件数 0、自动暂停数 0。
