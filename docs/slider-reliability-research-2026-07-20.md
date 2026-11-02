# 闲鱼连接与滑块可靠性调研（2026-07-20）

## 生产问题结论

账号 3 在 2026-07-19 18:32 至 2026-07-20 05:32 之间每小时主动刷新一次 WebSocket Token。每次刷新都会先关闭正常连接并把本地 Token 标记过期，再调用闲鱼 Token API。连续刷新约 11 次后，闲鱼返回 `FAIL_SYS_USER_VALIDATE`，账号进入等待滑块状态。

这不是 Cookie 登录状态失效。`hasLogin` 可以返回有效，但 WebSocket Token API 仍可能单独触发风控。自动回复依赖 WebSocket 收到消息，因此 WebSocket 停止时，即使自动回复配置开关仍开启，也无法接收或回复新消息。

同时确认两个本地缺陷：

- `FAIL_SYS_USER_VALIDATE` 分支没有调用已配置的邮件告警服务。
- 滑块出现后，Token 刷新任务每 300 秒再次请求一次，持续放大风控。

## 开源项目对比（只读核验）

### 固定参考提交

- 目标 SHA：`3d72207ae9b8f199cba6cd038dcf83b7fbec76be`
- 全局只解析到 [zhinianboke/xianyu-auto-reply](https://github.com/zhinianboke/xianyu-auto-reply/commit/3d72207ae9b8f199cba6cd038dcf83b7fbec76be)
- 许可证：AGPL-3.0
- 本项目许可证：Apache-2.0
- 边界：只借鉴状态机与运维思路，不复制其源码，也不把 AGPL 代码混入本仓库

`openfish/XianyuAutoAgent` 与 `openfish-ai/XianyuAutoAgent` 当前无法解析。`shaxiu/XianyuAutoAgent` 存在，但不包含上述固定提交，不能当作指定参考版本。

### 公开问题证据

- [#112](https://github.com/zhinianboke/xianyu-auto-reply/issues/112)：Token 刷新触发验证并最终掉线；关闭时没有可核验的修复提交。
- [#247](https://github.com/zhinianboke/xianyu-auto-reply/issues/247)：仍为 open；“滑块视觉成功但没有新 `x5sec`，账号仍离线”。
- [#254](https://github.com/zhinianboke/xianyu-auto-reply/issues/254)：仍为 open；手动更新 Cookie 后先提示验证成功，随后又达到重试上限。
- [#31](https://github.com/zhinianboke/xianyu-auto-reply/issues/31)：频繁“被挤爆”告警并停止回复，证明错误分类和高频刷新会形成告警风暴。

### 可借鉴机制

- 明确连接状态：网络断线、认证失败、风控暂停应分开处理。
- 协议 Ping 与业务心跳并用，重连采用指数退避和随机抖动。
- Token 缓存区分当前、续期、过期；条件更新防止旧结果覆盖新结果。
- Token API 失败时仍可合并响应中的新 Cookie；单纯 `_m_h5_tk` 过期只做有限重试。
- 滑块结果不能只看页面视觉成功，必须检查真实凭证或后续 Token 成功。
- 告警按事件类型去重，只有渠道真实发送成功后才进入冷却。
- Cookie 获取失败时保留旧 Cookie，成功后再切换并恢复连接。

### 不应照搬的部分

- 只等待约 1 秒就标记在线，不强制等待非空 `reg-sid` ACK。
- 多个 Token / Cookie 刷新所有者并存，容易重复请求。
- CAPTCHA 失败后高频重试，持续撞风控。
- 滑块失败立刻清 Token 并切断仍可能有效的连接。
- 日志打印完整 Token / Cookie。
- 远程滑块求解服务、未知镜像或 Cookie 外传。
- 将“被挤爆啦”误判为验证码或 Cookie 失效。

### CookieCloud

- [easychen/CookieCloud](https://github.com/easychen/CookieCloud) 是 GPL-3.0，可自托管、客户端加密、按域名过滤。
- 它只能同步已登录浏览器的 Cookie，不能消除平台验证码，也不能保证服务器 IP 永远放行。
- [OnlineMo/Goofish-Auto-reply-replace](https://github.com/OnlineMo/Goofish-Auto-reply-replace) 与 [RickForYK/xianyu-auto-reply-cookiecloud](https://github.com/RickForYK/xianyu-auto-reply-cookiecloud) 已归档，且存在许可证/来源风险；本轮不复制。
- 若后续采用，只允许独立自托管 CookieCloud 服务 + 仅同步 `goofish.com`，禁止公共实例、未知 solver 和 Cookie 外传。

## 采用方案

1. 以数据库中的 Token 实际过期时间为依据，仅在临近过期前 30 分钟刷新；健康连接不再每小时重启。
2. `WebSocketServiceImpl` 作为 websocket_token 自动到期刷新的唯一调度所有者。
3. 收到滑块要求后暂停普通重连和 Token 重试，改为递增退避低频探测：约 30 分钟、1 小时、2 小时、4 小时、6 小时封顶，并带最多 10% 向前抖动。
4. 人工确认、扫码/Cookie 更新或探测成功后清零退避并立即恢复。
5. 握手后必须等待非空 `reg-sid` ACK；自动回复只有服务端确认成功才记为成功。
6. 首次进入滑块状态发送独立滑块邮件；只有真实投递成功才去重，失败可重试。
7. UI 区分“自动回复已开启”和“已开启，当前暂停”。
8. “被挤爆啦”按临时繁忙处理，不标记滑块、不清 Cookie。

## 不采用方案

- 不集成来源不明的远程滑块求解服务或陌生容器镜像。
- 不自动模拟或破解平台验证码。
- 不把 Cookie、Token、验证 URL 或邮箱授权码写入日志、仓库或告警正文。
- 不承诺第三方平台风控下绝对 100% 在线；目标是降低触发率、可靠告警和可验证恢复。

## 本轮实现边界

无法合法承诺平台风控下绝对 24 小时无人值守。本轮能交付的是：

- 减少主动触发滑块
- 不中断仍有效连接
- 可靠邮件告警
- 人工完成平台验证后自动恢复
- 失败时不伪装成在线或发送成功
