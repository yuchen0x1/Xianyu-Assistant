# 代码质量与 UI 体验审计报告

- 审计对象：闲鱼卖家运营控制台 `v3.4.10`
- 审计日期：2026-11-02
- 审计范围：后端 599 个 Java 文件 / 72,013 行；前端 184 个 Vue/TS 文件 / 约 13,700 行
- 方法：静态扫描 + 分模块逐行代码走查（认证授权、发货与卡密、连接与 AI、任务与集成、前端、UI）；关键结论均已回读源码核对行号
- 未执行：未运行应用、未做动态压测、未跑 `vue-tsc`（`vue-code/node_modules` 缺失）。所有"运行期"结论均为代码路径推演，标注了触发前提

---

## 一、执行摘要

综合评分 **6.2 / 10**——工程底子明显好于同规模个人项目，但存在若干"看起来有防护、实际留了缝"的高危缺陷。

| 维度 | 评分 | 判断依据 |
|---|---|---|
| 架构与分层 | 6.0 | 141 个 Controller / 130 个 Service，职责切分基本清晰；但存在 5,000 行级巨型类与 Controller 直连 Mapper |
| 异常处理 | 5.0 | 617 处 `catch (Exception)`，失败语义系统性偏乐观，把"不确定"错判成"确定失败" |
| 并发与数据一致性 | 5.0 | 有幂等键、CAS 领取、乐观更新；但卡密状态机缺中间态、锁粒度粗、补偿未接线 |
| 安全 | 7.0 | JWT `verifyWith`、SSRF DNS pinning、日志下载 realpath 防护都做对了；失分在内部权限边界 fail-open |
| 前端工程 | 6.5 | 请求层单飞刷新 + 401 重放写得很好；失分在巨型组件、复制粘贴、错误提示双轨 |
| UI / UX | 6.5 | `tokens.css` 设计系统已建好，但业务页面仍并存三代历史色板 |
| 测试 | 6.0 | 96 个后端测试文件 / 13,632 行，覆盖了主要链路；前端仅 7 个测试文件 |
| 可维护性 | 5.0 | 最大单文件 5,009 行；三份分页算法、六份顶栏渲染、两份连接的重复实现 |

**最该先修的 5 件事**（按"止血收益 ÷ 成本"排序）：

1. 心跳脱离账号生命周期锁（约 0.5 天，消除假死误判与重连风暴）
2. 发货异常一律升级为 `outcomeUnknown`，禁止立即回滚卡密（约 0.5 天，直接消除重复发卡资损）
3. 备份导出/导入凭据加 ADMIN 门禁 + 账号范围过滤（约 0.5 天，消除"一个运营账号接管全部店铺"）
4. 账号隔离改为默认拒绝（约 1 天，配合切面对字符串型 id 的严格解析）
5. Webhook 幂等键改 `orderId` 优先（约 10 分钟，消除订单事件静默丢失）

前 3 项合计约 1.5 人日，可消掉本报告 3 条 P0。**建议不要等"重构完再做"**——这些是概率性资损，不是体验问题。

---

## 二、问题分布

| 严重度 | 定义 | 数量 |
|---|---|---|
| **P0** | 可导致账号接管、资损（重复发卡/重复发货）、库存永久损失、服务整体停摆、用户被锁死 | 8 |
| **P1** | 特定条件下故障：事件丢失、任务重复执行、静默漏回、告警吞没 | 14 |
| **P2** | 健壮性/性能/可观测性缺口，不直接造成错误结果 | 17 |
| **P3** | 代码质量、死代码、可维护性 | 11 |

按模块：

| 模块 | P0 | P1 | 主要风险性质 |
|---|---|---|---|
| 安全与权限 | 2 | 2 | 内部越权、凭据外带 |
| 发货 / 卡密 | 3 | 4 | 资损、库存泄漏 |
| 连接 / AI 回复 | 2 | 4 | 可用性、对外事故 |
| 任务 / Webhook / 备份 | 1 | 4 | 事件丢失、重复执行 |
| 前端 | 1 | 3 | 功能死锁、视图错乱 |
| UI | 0 | 0 | 一致性与效率（见第六章） |

---

## 三、P0 问题清单

### P0-1 备份导出可拿到全部明文 Cookie / Token，只需 `backup:write`

- **状态**：ADMIN 门禁已修复（见第九章）；账号范围过滤仍待 P0-2 完成后补齐
- **位置**：`controller/DataBackupController.java:94-99`、`backup/handler/AccountBackupHandler.java:87-89`、`src/main/resources/db/migration/V3_6__rbac_init.sql:113-118`
- **事实**：导出接口只校验 `@RequirePermission("backup:write")`；该权限在预置 `OPERATOR` 角色内。`AccountBackupHandler` 对整个 `xianyu_cookie` 表 `selectList(null)`，无账号范围过滤；`includeSecrets=true` 时 `cookieText` / `mH5Tk` / `websocketToken` 直接输出**解密后的明文**（TypeHandler 已解密）。同包还会带出 `ai_api_key`、`email_smtp_password`。
- **影响**：一个运营账号一次 POST 即可导出所有店铺的凭据，直接接管全部闲鱼账号。这是本报告中唯一"单人单次操作即可造成全量失陷"的路径。
- **修复**：`includeSecrets` 必须额外满足 ADMIN 角色（复用 `SysSettingController.java:118-123` 的判定写法）；`AccountBackupHandler` 增加 `userId` 入参并调用 `rbacService.getVisibleAccountIds()` 过滤；导出含密钥时要求重新输入登录口令，并写审计日志。

### P0-2 账号数据隔离 fail-open：未配置范围 = 全部可见，且字符串型 id 可绕过切面

- **位置**：`rbac/service/RbacService.java:284-289`、`rbac/aspect/AccountScopeAspect.java:62,88-90`
- **事实**：
  ```java
  List<Long> scopeIds = getUserAccountScopeIds(userId);
  return scopeIds.isEmpty() ? null : scopeIds;   // null = 不限制
  ```
  `assertAccountVisible` 与切面都把 `null` 解释为"不限"。管理员新建 VIEWER/OPERATOR 后，只要没去 `/api/rbac/users/{id}/accounts` 配范围，该用户就能读全部账号的订单、会话、消息、卡密。此外切面只识别 `Number` 类型的参数值，`{"accountId":"7"}` 这种字符串形态会被直接忽略，但业务代码 `Long.valueOf(params.get("accountId").toString())` 照常执行。
- **影响**：隔离能力实际依赖"记得去配置"；默认部署下非管理员等同拥有全量只读权限。字符串绕过可进一步变成**写操作越权**（如 `GoodsTemplateController.java:103` 的批量上架、`ItemController.java:389`）。
- **修复**：非 ADMIN 的 `scopeIds.isEmpty()` 返回 `List.of()`（默认拒绝）；`setUserAccountScope` 的空列表语义拆成显式 `clearScope`；切面对 `CharSequence` 也做 `Long.parseLong`，解析失败一律 403 而不是忽略。

### P0-3 卡密"预占"与"已使用"共用 `status=1`，UNKNOWN 或进程崩溃后永久占库

- **状态**：已修复（2026-11-03，见第十章）
- **位置**：`service/delivery/KamiDeliveryStrategy.java:122-137`、`mapper/XianyuKamiItemMapper.java:80`、`job/service/DeliveryCompensationService.java:73`
- **事实**：取卡即 `SET status=1, order_id=?`，没有 RESERVED 中间态；回滚能力只存在于内存回调 `DeliveryReservation.rollbackWith(...)`，进程一挂就消失。补偿服务里 `recordUnknown(...)` **全仓库零调用**，`DELIVERY_REVIEW` 分支只读本地 state，从不释放卡密。
- **影响**：每出现一次"结果未知"或一次崩溃，就静默损失一张卡密。`availableCount` 单调下降，页面只显示"已使用"，运营无法发现，最终表现为无故缺货。
- **修复**：引入 `delivered_time` 作为第三态标记（`status=1 && delivered_time IS NULL` = 预占待确认），`commit` 才写入交付时间；新增 5 分钟一轮的滞留巡检，超 30 分钟未确认的预占按订单终态收敛——成功补确认、失败释放、无法判定则告警留人工。**未采用原建议的 `status=2`**，原因见 9.6。

### P0-4 异常兜底判 `FAILED` → 回滚已发出的卡密 → 同一张卡卖给两个人

- **状态**：已修复（5 处兜底路径）
- **位置**：`service/impl/OrderServiceImpl.java:144`、`utils/XianyuApiCallUtils.java:312`、`adapter/xianyu/XianyuAdapterImpl.java:68`、`service/impl/AutoDeliveryServiceImpl.java:583`、`service/impl/WebSocketServiceImpl.java:1941`
- **事实**：请求失败语义分两层。底层 `HttpClientUtils` 已经正确区分 `noResponse()`（已发出、无响应）与 `notSent()`（未发出），`XianyuApiCallUtils.java:203` 据此设置 `outcomeUnknown`——**这条主链路原本是正确的**。真正的缺口是所有层的兜底 `catch (Exception)`：它们一律返回 `FAILED`，而消费端遇到 `FAILED` 就执行 `rollbackReservations(...)`。
- **复核修正**：审计初稿把 `:312` 描述为"主因"并不准确，实际是"兜底异常路径"这一圈；严重度维持 P0（后果是重复发卡），但触发概率低于初判——需要异常穿透到兜底 catch，而非普通超时。
- **影响**：请求已写出、闲鱼侧已把卡密落到订单，但本地在兜底路径判定"失败"并把卡密退回库存，下一单再发同一张。**真实资损 + 客诉**。
- **修复**：`catch` 中的返回值改为 `outcomeUnknown=true` / `ApiDeliveryResult.UNKNOWN` / `MessageSendResult.UNKNOWN`，让上层保留预占等待核对，而不是即时回滚。

### P0-5 多入口并发发货同一订单，一方失败会回滚另一方已送达的卡密

- **状态**：已修复（2026-11-03，见第十一章）
- **位置**：`service/delivery/KamiDeliveryStrategy.java:127-137`、`service/ApiDeliveryScheduler.java:226`、`job/service/PendingOrderPollService.java:52`
- **事实**：`/deliverPendingOrders`、`ApiDeliveryScheduler`（25s 一轮）、聊天事件监听、手动重试四条路共享同一订单，互斥只靠事后检查 `state==1`。复用已有预占的一方不持有回滚权，但**原始预占方**失败时仍会执行 `releaseKamiReservation`。
- **影响**：A 预占 card1 → B 复用并发送成功 → A 发送失败触发回滚 → card1 回到 `status=0`，下一单再卖一次。
- **修复**：按「账号 + 订单号」加进程内互斥租约，凡"占卡 + 对外发送"的入口（事件监听、API 兜底、手动触发、手工发货）都必须先拿到租约；拿不到即放弃，由持有者写最终状态。另外在发货前按订单终态兜底拦截"换 pnmId 重推导致的二次发货"。**未采用原建议的 `delivery_phase` 列**，原因见 11.1。

### P0-6 心跳与消息发送 / 重连共用一把账号级锁，健康连接被误判为掉线

- **位置**：`service/impl/WebSocketServiceImpl.java:1043`（心跳持锁）、`:1913`（发送持锁）、`websocket/XianyuWebSocketClient.java:975`（等响应 10s）、`config/WebSocketConfig.java:20-26`（超时 15+5s）
- **事实**：心跳 `scheduleAtFixedRate` 的整个 tick 包在 `withLifecycleLock` 内；发送同样持锁且内部最长等 10s；重连路径在锁内做 `checkLoginStatusQuietly`（OkHttp connect 30s / read 60s / 重试 2 次）+ 浏览器兜底。心跳线程池只有 4 个线程。
- **影响**：一条会话连续两条回复、或一次 Token 刷新持锁 >20s，心跳线程即被阻塞；线程释放后 `now - lastResponse > timeout` 成立，系统关掉一条**完全健康**的连接并重连。多账号并发时形成重连风暴，在途消息丢失。表现是"随机掉线"，极难定位。
- **修复**：心跳彻底不取生命周期锁（`webSocketClients` 是 `ConcurrentHashMap`，用 `if (get(id) != client) return;` 做代际校验即可，`onHeartbeatResponse` 已是这种写法）；发送路径锁内只取引用，锁外 send + 等待；超时判定加二次确认（连接仍 `isOpen()` 且再等一周期）。

### P0-7 AI 主客户端无超时、无重试上限，4 个 job worker 被占满后自动回复与发货同时停摆

- **位置**：`config/rag/DynamicAIChatClientManager.java:244`（裸建客户端，无 requestFactory）、`:250-270`（只给"润色"客户端设了超时/关重试）、`service/impl/AIServiceImpl.java:554`（自动回复阻塞 `.call()`）、`job/service/JobEngine.java:89`（worker 池 = 4，队列 32，`AbortPolicy`）
- **事实**：同文件里润色客户端设了 `connect 5s / read 35s` 且 `maxAttempts(1)`，说明作者知道这个坑，但主链路漏了。
- **影响**：上游半开连接 / 长时间不吐字时，4 个 `job-worker` 被永不返回的调用占满 → 队列 32 满后丢弃 → **自动回复和自动发货（同一 JobEngine）一起停**，且没有超时日志，只能靠线程 dump 定位。
- **修复**：标准客户端也注入 `SimpleClientHttpRequestFactory`（connect 5s / read 35s）+ `retryTemplate maxAttempts(1)`；外层加 45s 预算 `CompletableFuture.get(45, SECONDS)` 超时降级；AI 调用与发货用不同线程池隔离。

### P0-8 前端 `/login` 与 `/dashboard` 互相硬跳，权限接口异常时整页刷新死循环

- **位置**：`vue-code/src/router/index.ts:171-181`、`vue-code/src/views/login/index.vue:73-76`
- **事实**：守卫在 `loadPermissions()` 失败时 fail-closed 跳 `/login`；登录页 `onMounted` 见 `isLoggedIn()` 为真就 `window.location.href = '/dashboard'`——是整页重载，不是 SPA 跳转。
- **影响**：token 有效但权限接口 5xx / 网络抖动 / 角色缺 `dashboard:read` 时，两个页面互相强制刷新，白屏闪动、后端被每秒数次请求打满，用户清 token 也无法恢复（登录成功后进入同一循环）。
- **修复**：登录页跳转前 `await loadPermissions()`，失败则停在登录页给"服务异常 + 重试"；守卫 catch 分支跳到独立错误页（不带权限校验）并带 `?reason=perm_unavailable`；`fallback` 改 `next(false)` + toast，禁止两页互踢。

---

## 四、P1 问题清单

> **状态更新（2026-11-03）**：除 #4（租约 fencing，属架构级改造，见 12.4）外，
> 本表 18 条均已在第十二章修复。下表保留原始记录以便对照。

| # | 问题 | 位置 | 影响 | 修复要点 |
|---|---|---|---|---|
| 1 | 备份导入可写 `ai_base_url`，绕过"仅 ADMIN 可改" | `backup/handler/SystemSettingBackupHandler.java:17-18,76-105` 对照 `SysSettingController.java:118-123` | OPERATOR 导入自定义备份把 AI 端点指向自己的公网服务器，下次调用时 `Authorization: Bearer <ai_api_key>` 外带 | 这两个键从 `BACKUP_KEYS` 移除，或导入时复用 `AiEndpointValidator` + ADMIN 判定 |
| 2 | Webhook 幂等键优先取 `accountId` | `webhook/service/WebhookService.java:323` | 同账号同分钟两笔订单的 `delivery.completed` 生成同一个 key，UNIQUE 冲突被 `continue` 静默丢弃，集成方永久收不到 | 业务键改 `orderId` 优先；冲突时不 `continue`，标记 duplicate 并计数 |
| 3 | Outbox 的 `PENDING` 无兜底扫描 | `WebhookService.java:312-316,414-418` | 落库后进程被杀 → 永远 PENDING，重投只扫 FAILED，事件实质丢失 | 扫描改 `status IN ('PENDING','FAILED')` + `created_time < now-2min`；启动时把陈旧 PENDING 置 FAILED |
| 4 | 租约续期与轮询共用单线程调度器，终态回写无 fencing | `job/service/JobEngine.java:102-107,391-401,323-336,470-471` | SQLite 写锁争用时续期失败 → 仍在跑的任务被判死并二次派发 → **重复回复 / 重复发货**；旧 worker 结束时覆盖新状态 | 续期独立 `ScheduledExecutorService`；加 `lease_token`，续期与终态回写都带 token + status 条件 |
| 5 | 告警状态机单向，`RESOLVED` 后同一问题永久静音 | `monitor/mapper/AlertEventMapper.java:33-36`、`monitor/AlertService.java:40` | 运维点"恢复"后问题复发不再告警；`delivery_failure` 不带 accountId，多账号互相覆盖 | `trigger` 时若已 RESOLVED 则重置为 ACTIVE；键改 `delivery_failure_{accountId}`；加 reopen 动作 |
| 6 | 告警发送结果用单例可变字段传递 | `notify/NotifyService.java:103` 读于 `monitor/service/AlertEventService.java:69` | 并发告警互相读到对方结果 → 真实失败被判成功 → 推进 30 分钟抑制窗口，通知被吞 | 改为返回 `SendResult`，删除共享字段；零渠道返回 `NO_CHANNEL` 且不推进窗口 |
| 7 | 发货成功（`state=1`）后任何后续异常把状态覆写成失败 | `service/impl/AutoDeliveryServiceImpl.java:640-662` | 消息与卡密已送达，却触发失败邮件 + `delivery.failed` Webhook + 兜底再发一次 | `state=1` 之后的副操作移出 try；`updateRecordState` 加 `WHERE state<>1` 幂等守卫 |
| 8 | 兜底发货对坏单无限重试，无上限无退避 | `service/ApiDeliveryScheduler.java:246-259`（`fixedDelay=25000`） | 单笔坏单每 25s 打一次 API，占满账号限流，拖累同账号所有订单；告警洪泛 | 加 `attempt_count` / `next_retry_at`，CAS 递增，指数退避，超限转人工 |
| 9 | 自动回复失败被系统性记为成功，且唯一索引导致永久漏回 | `job/config/JobWorkerRegistry.java:79`、`service/impl/AutoReplyServiceImpl.java:310-324`、`schema.sql:338` | Worker 恒返 true；UNKNOWN 记为已回复；重推消息因 `pnm_id` 唯一索引被跳过 → **该买家永久无回复，看板显示成功** | `executeAutoReply` 返回 `SUCCESS/FAILED/UNKNOWN`；去重表加 status，失败允许 N 分钟内重试一次 |
| 10 | 延时回复 `pendingMessages` 用可变 ArrayList 共享且成功不清理 | `service/reply/AutoReplyDelayServiceImpl.java:60,103-113,129` | 序列化时 `ConcurrentModificationException` → 参数丢失 → 回复拿不到内容；每条带完整解密 JSON，长期内存单调增长 | `compute` 内复制新 List 并 `List.copyOf` 传参；执行完清理；入队前清空 `completeMsg` |
| 11 | `vectorstore.json` 非原子保存、无锁，损坏后静默清空 | `config/rag/DynamicVectorStoreManager.java:171-181,211-218` | 并发保存写出半截 JSON → 下次启动加载失败只 warn → 以**空向量库**运行，所有知识库静默失效 | 写 `.tmp` 再 `ATOMIC_MOVE`；`add/save` 走写锁；损坏文件重命名保留并告警 |
| 12 | 每小时清理会删掉正在使用的 Chromium profile 目录 | `config/PlaywrightManager.java:196,203-206` + `TokenRefreshServiceImpl.java:714-721` | 目录不判年龄直接递归删，命中 `playwright_chromiumdev_profile-*` → 浏览器崩溃 → "隔一段时间就要重新验证/掉登录" | 目录也加 1 小时年龄判断；浏览器存活时跳过；或改用独立 `userDataDir` 自管生命周期 |
| 13 | 事务内发 SMTP 邮件 / Webhook | `service/delivery/KamiDeliveryStrategy.java:44` → `KamiConfigServiceImpl.java:350,549,556` | SMTP 超时期间持有 SQLite 写锁，`busy_timeout=5000` 一过其他写全 `SQLITE_BUSY` → 并发发货连环失败 | 告警只做 COUNT，邮件/Webhook 走 `@TransactionalEventListener(AFTER_COMMIT)` |
| 14 | `@Transactional` 方法内吞异常返回 `failed` → 部分提交 | `service/impl/KamiConfigServiceImpl.java:199-233,152-165,271-287` | 导入 1000 条第 N 条失败，前 N-1 条已落库；用户重试再导一遍 → 重复卡密 | 事务方法内不 catch，让异常穿出代理；批量导入改 `INSERT OR IGNORE` |

补充 P1（前端）：

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 15 | AI 流式接口裸 `fetch`，无超时、无卸载中断 | `vue-code/src/api/ai.ts:55-62`、`views/auto-reply/useAutoReply.ts:1113-1119` | `chatSending` 永不复位，发送按钮/输入框锁死，只能刷新页面；绕过 axios 拦截器（无 401 续期、无统一提示） |
| 16 | 商品同步进度轮询无终止条件 | `views/goods/useGoodsManager.ts:56-83` | 失败即 1 次/秒无限轮询，`syncing/refreshing` 永不复位，刷新按钮永久转圈不可点 |
| 17 | 消息 3 秒轮询把第 1 页新消息合并进第 N 页 | `views/messages/index.vue:204-208`、`useMessageManager.ts:146-155` | 第 2 页出现第 1 页数据，`total` 与渲染条数不符，翻页重复或丢失 |
| 18 | `/connection/:id` 两套 1000+ 行实现，桌面端同时跑两路 10s 轮询 | `views/connection/ConnectionDetail.vue:325-350` 与 `components/ConnectionDetail.vue:290-296` | 每 10 秒 5 个请求互相覆盖；逻辑逐字复制，修复必然漂移 |

---

## 五、P2 / P3 清单（择要）

**P2（健壮性 / 性能 / 可观测性）**

- 登录限流只看 IP、存进程内、无账号维度；反代忘配 `trusted-proxies` 时一人失败 5 次锁全体用户（`service/impl/AuthServiceImpl.java:282-323`）
- JWT 本地密钥文件未收敛权限（`util/JwtUtil.java:162-175`，对比 `security/CredentialCipher.java:206-213` 有 `rw-------`）
- 并发抢占二次失败直接返回 `null`，被翻译成"卡密库存不足"并发缺货邮件（`KamiConfigServiceImpl.java:342-346`）
- `buyNum` 缺失时默认为 1 → 买家拍 3 件只发 1 张（`AutoDeliveryServiceImpl.java:416-417`）
- 备份"先裁后验"：写临时文件 → 直接 move → 再 trim，坏文件会顶掉可用备份（`service/DataBackupScheduler.java:93-101`）**→ Sprint 4 已修「空/半截文件顶掉可用备份」，见 13.2**
- 备份导出把全表聊天记录读进内存再拼单个 JSON（`backup/handler/ChatLogsBackupHandler.java:43-47`），十万级消息有 OOM 风险 **→ Sprint 4 已修，见 13.2**
- 备份导入单事务持写锁数十秒、无导入前快照（`DataBackupServiceImpl.java:117-118`）
- Webhook 发送池仅 2 线程、内联重试不退避，慢端点会队头阻塞全部事件（`WebhookService.java:113-120,363-390`） **→ Sprint 4 已修，见 13.3**
- 共享异步池 `CallerRunsPolicy`，饱和时在 WebSocket 读线程上跑自动回复/发货（`config/AsyncConfig.java:62-68`）
- 每连接一个无界单线程执行器，`onError` 后消息静默丢弃（`websocket/XianyuWebSocketClient.java:55-59,687`）
- 重连有指数退避但无抖动、计数多处清零、无上限（`WebSocketServiceImpl.java:1606-1612`、`WebSocketConfig.java:114` 字段未使用）
- 后台线程调 `cancelJob` 必返 false，人工接管后的延时任务取消不掉（`JobEngine.java:534-536`）
- 告警/Webhook 多处用"先查后改"而非原子抢占，重复投递（`WebhookService.java:425-448`）
- MCP `tools/call` 不校验参数 schema，异常原文回显（`mcp/McpServerController.java:186-216`）
- 卡密表零分页，全量 `v-for`（`vue-code/src/views/kami-config/index.vue:642`）
- 前端错误提示双轨：拦截器已 toast，页面再 `showError` 一次（`utils/request.ts:122-129` 对比 `views/connection/ConnectionDetail.vue:141-143`）
- 原生 `alert/confirm` 与自研 `showConfirm` 混用（`views/pending-orders/index.vue:120`、`ConnectionDetail.vue:131,168,186`）

**P3（代码质量）**

- 5,009 / 5,006 / 4,937 / 4,078 行级巨型类（`WebSocketServiceImpl` 2,101 行只是其中较小的一个）
- `handleAutoDelivery` 死代码且未替换 `{kmKey}` 占位符（`AutoDeliveryServiceImpl.java:223-228`）
- `sumDeliverySuccessAmount` 用 `CAST(total_price AS REAL)` 求和，对账会出现分位误差（`XianyuGoodsOrderMapper.java:231`）
- `configLocks` / `WriteRateLimiter.windows` 只增不删（`KamiConfigServiceImpl.java:69`、`limit/WriteRateLimiter.java:21`）
- `request<T = any>` 让 151 个 api 函数基本失去类型检查（`utils/request.ts:168`）
- Pinia 已注册但 `defineStore` 出现 0 次，权限缓存 `clearPermissionsCache()` 定义后 0 调用
- 三份 `getPageButtons()` 逐字复制、六份 `h()` 手搓顶栏、13 份状态色映射
- 登录失败文案区分"账号已被禁用"与"用户名或密码错误"，可枚举账号（`AuthServiceImpl.java:158-160`）

---

## 六、UI / 体验评估

### 6.1 现状定性

**"设计系统已经建好，但只在外壳用上"的双轨界面。** `styles/tokens.css` 定义了完整的双主题令牌（语义色、`--z-*`、`--space-*`、动效曲线、`prefers-reduced-motion` 兜底），质量相当高；但业务页面仍并存三代历史色板：Apple（`#0071e3` / `#007aff`）、Material（`#ff9800` / `#f44336` / `#4caf50`）、Flat UI（`#34495e` / `#95a5a6`）。

量化：390 处硬编码 hex 色值（最高频 `#fff` 52 次、`#0071e3` 11 次、`#f5f5f7` 9 次），`rgba(` 273 处；`.btn` 在 15 个文件各自重定义。

做得好的三处：

1. `styles/tokens.css` —— 令牌体系完整，含 `color-mix()` 派生与减动效兜底
2. `views/login/index.vue` —— 表单可访问性教科书级：`aria-invalid`、`aria-describedby`、`role="alert" aria-live="assertive"`、`autocomplete`、手写 `novalidate` 校验
3. `views/pending-orders/index.vue` —— 全项目唯一把加载 / 空 / 错误三态拆开写全的页面，带 `role="status"`、`role="alert"` 与重试按钮，应作为其余页面的样板

### 6.2 体验问题

**信息架构**
- `/pending-orders` 是孤儿页：`router/index.ts:73` 标 `hidden: true`，全项目无入口。而"待发货"是时效最紧的任务，却找不到
- 侧栏分组名冲突：`NavMenu.vue:60-77` 同时存在「系统」与「系统管理」，17 项里无法凭组名预判"监控中心"在哪
- 仪表盘默认页混入营销内容：快速开始 4 步、功能特性 6 卡、FAQ 4 条硬编码在组件内（`dashboard/index.vue:63-95`），老用户每次都要穿过教程

**视觉一致性**
- 三套按钮并存：`.btn.btn--primary`（54 处）、`.btn-primary/.btn-danger/...`（26 处）、令牌化的 `.ui-btn--*`（仅 3 处）
- 状态色映射 13 份副本（`OrderTable.vue:148`、`GoodsTable.vue`、`AccountTable.vue` 各写一份），且 `OrderTable.vue:156` 用 `rgba(255,159,10,.18)` 而非 `var(--warning-50)`，暗色下亮度不齐
- 令牌尺度是假的：`--radius-md/lg/xl` 全等于 `8px`；字号 11/12/13/14 四档相邻 1px，形成不了层级

**状态反馈**
- 弹窗不跟随暗色主题：`utils/toast.ts` / `utils/confirm.ts` 用 `document.createElement` 注入内联样式，硬编码白玻璃 `rgba(255,255,255,0.72)` 与 `#0A84FF`
- 连接状态被压成二值：`ConnectionCard.vue:52` 把 `reconnecting`、`captchaPending` 与真正断开统一显示为红色"未连接"，卖家会把正常重连误判为事故
- 加载态与空态共用 `.kami-page__empty`（`kami-config/index.vue:620,622`），只靠文字区分，无骨架屏
- 全局顶栏只显示本机服务健康，不显示"自动化是否真的在跑"

**效率（高频路径）**
- 卡密表零分页（`kami-config/index.vue:642`），几千条卡密首屏卡死
- 隐藏双击交互：`OrderTable.vue:248` 的"单击查本地 / 双击查闲鱼服务器"用 300ms 定时器区分，用户永远不知道双击存在，且单击白等 300ms
- 发货记录工具栏 9 个平级控件，其中"导出"与"导出 Excel"语义重叠（`orders/index.vue:270-305`）
- 危险操作无差异化：删除卡密与重置用同一个中性蓝按钮（`kami-config/index.vue:273`）

**可访问性**
- 约 30 个弹窗只有 9 个有 `role="dialog"`；Esc 关闭只在 3 个外壳文件实现，页面级弹窗无法键盘关闭、无焦点陷阱
- 断点碎片化：246 处 `@media` 用了 7 种等价写法（`767.98px` / `768px` / `768.98px` / `480px` / `900px` / `1023.98px` / `1024px`）
- 层级失控：`z-index: 2000` 出现 10 次、`1000` 出现 5 次，而令牌里 `--z-modal: 70`

### 6.3 升级方案

**阶段一：让已有令牌生效（1–2 天，低风险）**

| 动作 | 涉及文件 | 验收 |
|---|---|---|
| `toast.ts` / `confirm.ts` 改为读令牌，`confirm` 增加 `variant: 'danger'`，危险场景用 `--danger-500` 且文案含"不可撤销" | `utils/toast.ts`、`utils/confirm.ts`、`styles/tokens.css` | 暗色模式下 toast/confirm 无白底；`grep confirm(` 归零 |
| 新增 `UiStatusBadge.vue`，把 13 份状态映射收敛为 `success/warning/danger/neutral/unknown` 五档，`unknown` 用灰不用红 | 新建 `components/ui/` | 状态映射函数 13 → 1 |
| 新增 `UiSkeleton.vue`、`UiError.vue`（带 retry 插槽），对标 `pending-orders` | 同上 | 加载/空/错三态有独立组件 |
| `ConnectionCard.getWsText` 拆状态：`reconnecting`→琥珀"重连中(12s)"、`captchaPending`→琥珀"待验证"、`disconnected`→红 | `views/connection/components/ConnectionCard.vue` | 三种状态可区分 |

**阶段二：三个高频页重构（约 1 周）**

*订单/发货列表*：`table-layout: fixed` + `<colgroup>`，10 列压到 7 列（订单ID 180 / 商品 1fr / 状态 96 / 买家 96 / 金额 88 / 时间 140 / 操作 120），规格并入商品单元格第二行；行内只留主按钮「发货」，详情/复制进 `⋯` 菜单，删除双击逻辑；工具栏把"导出 + 导出 Excel"合并为「导出 ▾（CSV / Excel）」；状态筛选用分段控件替代开关按钮；加 `compact / standard` 密度切换，行高 32/44px 存 localStorage。
验收：1440px 下无横向滚动；订单表列数 ≤7。

*卡密库存*：补服务端 `pageNum/pageSize`（50/页）；删除/重置换 danger 确认；批量导入加进度条；库存水位列显示「剩余 / 预警阈值」双色条，低于阈值行标琥珀。
验收：5,000 行首屏 < 1.5s。

*仪表盘*：顶部加健康条，把 guide tab 降级为"0 数据时才默认展开"，FAQ/特性/温馨提示移入 `/settings` 帮助页；KPI「发货失败」可点击直跳 `?state=-1`。
验收：首屏可交互 < 1.2s。

**阶段三：设计系统组件化（2–4 周）**

抽 `UiTable`（列配置数组驱动，内置空/载/错、排序、选择）、`UiPagination`、`UiModal`（内置 Esc、焦点陷阱、`aria-modal`、`inert`）、`UiFilterBar`；删除 15 处重复 `.btn`，只留 `.ui-btn`；断点收敛为 640/1024/1440 三档；`isMobile` 双份模板并入 `UiTable` 的 `mobile-card` 模式；扩展快捷键（已有 `Ctrl+K`）为 `g d / g o / g k` 跳转 + `/` 聚焦搜索 + `?` 面板。
验收：`styles/` 外硬编码 hex 归零；`.btn` 定义 15 → 1；弹窗 `role="dialog"` 覆盖 100%；键盘可完成"查订单 → 发货"全链路。

### 6.4 仪表盘布局建议

```
┌ 账号▾   今/昨/自定义日期      最后更新 12s前 ↻ ──────────────┐
├ 健康条（新增，最高优先级）───────────────────────────────────┤
│ ● WS 3/4 在线   ▲发货失败 2  待核对 5   ◆卡密余量不足 1        │
├ KPI 4 联 ───────────────────────────────────────────────────┤
│ 今日订单 42 │ 发货成功 39 │ 发货失败 2(可点→) │ AI回复 118      │
├ 销售额趋势（2/3）────────────┬ 待办队列（1/3）───────────────┤
│ 折线 + 区间合计               │ · 2 笔发货失败  去处理 →       │
│                              │ · 1 个卡密库余量 <10           │
├ 发货成败（1/3）┬ 商品Top5（1/3）┬ 账号对比（1/3）───────────┤
└ 入门向导（仅 0 数据时展开，否则折叠为一行）──────────────────┘
```

理由：卖家打开控制台的第一问是"有没有出事"，不是"卖了多少"。健康条置顶、失败数可下钻、待办与图表同屏，把"发现异常"和"处理异常"压在一屏内。

*订单列表* 建议保留左侧商品树（与自动发货页心智一致），表格固定列宽避免翻页跳动，行内只留一个主操作。

---

## 七、修复路线图

| 批次 | 内容 | 预估 | 收益 |
|---|---|---|---|
| **Sprint 0（止血）** ✅ | P0-4 异常语义、P0-6 心跳脱锁、P0-1 备份门禁、P1-2 幂等键 | 1.5–2 人日 | 消掉 2 条资损级 + 1 条接管级，全部是局部改动 |
| **Sprint 1（一致性）** ✅ | P0-3/P0-5 卡密三态 + 发送租约 + 补偿扫描；P0-2 隔离默认拒绝 | 3–4 人日 | 库存不再泄漏；权限模型可承载多人协作 |
| **Sprint 2（可用性）** ✅ | P0-7 AI 超时与池隔离、P0-8 前端路由死循环、P1-3 任务租约 fencing（仅 P1-4 未做） | 3 人日 | 自动化不再"静默停摆"，任务不重复执行 |
| **Sprint 3（体验）** ✅ | UI 阶段一 + 前端 P1（AI 流超时、同步轮询终止、消息分页合并） | 2–3 人日 | 用户不再被卡死/看到错乱数据 |
| **Sprint 4（重构）** ⬜ | UI 阶段二、巨型组件拆分、`request<T>` 泛型收紧 | 1–2 周 | 可维护性，降低后续迭代成本 |

**不建议现在做的事**：换组件库、重写页面结构、把 SQLite 换 MySQL。当前瓶颈不是选型，是状态机与失败语义。

---

## 八、方法与局限

- 六路并行静态审计 + 主审抽查核实。P0 条目均经二次回读源码确认行号与逻辑，非推测
- 未运行动态测试：并发类结论（P0-3/4/5、P0-6）是基于代码路径与时序推演，建议为每条补一个可复现用例后再改
- 未做依赖漏洞扫描（`mvn dependency-check` / `npm audit`）与性能压测，这两项建议纳入下一次审计
- 报告中"行号"对应本次审计的 commit `1651d1c`，后续改动可能导致偏移

---

*本报告由代码审计生成，结论可直接转为 issue；建议按 Sprint 0 → 4 顺序落地，Sprint 0 不应与重构混在同一分支。*

---

## 九、Sprint 0 修复记录（2026-11-03）

### 9.1 改动清单

| 问题 | 文件 | 改动 | 状态 |
|---|---|---|---|
| P0-4 | `service/impl/OrderServiceImpl.java` | `consignDummyDelivery` 兜底 catch 由 `FAILED` 改为 `UNKNOWN` | 已改 |
| P0-4 | `utils/XianyuApiCallUtils.java` | `doCallApiWithRetry` 兜底 catch → `outcomeUnknown=true` | 已改 |
| P0-4 | `adapter/xianyu/XianyuAdapterImpl.java` | 降级 catch → `outcomeUnknown=true` | 已改 |
| P0-4 | `service/impl/AutoDeliveryServiceImpl.java` | 发送异常兜底 → `MessageSendResult.UNKNOWN` | 已改 |
| P0-4 | `service/impl/WebSocketServiceImpl.java` | `sendMessageWithOutcomeLocked` catch → `UNKNOWN`（"客户端不存在/未连接"仍为 `FAILED`） | 已改 |
| P0-6 | `service/impl/WebSocketServiceImpl.java` | 心跳 tick 不再持有账号生命周期锁；超时处理改用 `tryLock`，拿不到锁则下一 tick 重判 | 已改 |
| P0-1 | `controller/DataBackupController.java` | `includeSecrets=true` 叠加 ADMIN 角色校验 + 拒绝/放行各记一条告警日志 | 部分（范围过滤待 P0-2） |
| P1-2 | `webhook/service/WebhookService.java` | 幂等键改为 `orderId > kamiConfigId > goodsId > alertKey` 优先；`accountId` 降级为兜底并追加载荷摘要 | 已改 |

### 9.2 验证结果

- 编译：`mvnw -B -ntp -DskipTests compile` → **BUILD SUCCESS**（503 个源文件，30s）
- 定向测试：`XianyuApiCallUtilsOutcomeTest`、`XianyuWebSocketClientSendResultTest`、`OrderServiceImplDeliveryReservationTest`、`WebhookServiceTest`、`AutoDeliveryServiceImplTest`、`XianyuAdapterImplTest`、`WebSocketServiceImplTest`、`KamiDeliveryStrategyTest`、`ApiDeliverySchedulerTest` → **117 用例全绿**
- **回退了一处改动**：`awaitPendingResponse` 的 `ExecutionException` 分支保持 `FAILED`。既有测试 `XianyuWebSocketClientSendResultTest#mapsLocalWaitingFailureToFailed` 明确约定了"本地等待机制失败 = FAILED"，这是作者的显式设计，不应以"更保守"为由擅自改写；`InterruptedException` 改为 `UNKNOWN`（无测试约束，且中断后帧可能已写出）

### 9.3 行为变化与升级注意

1. 发货/发送路径的异常，现在落 `state=0`（结果未知、待核对）而非 `state=-1`（失败）+ 回滚卡密。
2. 因此**失败统计口径会变**：`-1` 减少、`0` 增加。看板与告警若按 `state=-1` 统计"发货失败"，需要同步调整，否则会低估失败量。
3. **配套要求**：本次只切断了"错误回滚"，没有补上"滞留预占的回收"。该配套件已在第十章完成——UNKNOWN 会让卡密进入"预占待确认"，由 5 分钟一轮的巡检在 30 分钟后按订单终态收敛或告警，不再静默泄漏。
4. 心跳改动后，`handleConnectionLost` 在拿不到锁时会延后一个 tick（1 秒），不影响断线判定，但日志里会多出 debug 级的"延后"记录。

### 9.4 剩余未做（建议顺序）

| 顺序 | 事项 | 理由 |
|---|---|---|
| 1 | ~~P0-3 卡密三态 + 滞留扫描~~ | 已在第十章完成 |
| 2 | ~~P0-5 按 `orderId` 的发送租约~~ | 已在第十一章完成 |
| 3 | P0-2 隔离默认拒绝 + 切面严格解析 | 同时补齐 P0-1 遗留的账号范围过滤 |
| 4 | P1-3 outbox PENDING 兜底扫描、P1-4 任务租约 fencing | 事件不丢、任务不重复 |

### 9.5 建议补的回归用例

- 让 `consignDummyDelivery` 抛 `RuntimeException`，断言返回 `UNKNOWN` 且卡密仍为预占状态（不释放）
- 模拟一次持锁 25s 的发送任务，断言同时到来的心跳 tick 不会触发 `handleConnectionLost`
- OPERATOR 角色请求 `includeSecrets=true`，断言返回校验失败且不产生数据
- 同账号同分钟分发两笔不同 `orderId` 的 `delivery.completed`，断言两条 `webhook_delivery` 都落库

---

## 十、Sprint 1 修复记录：P0-3 卡密三态与滞留回收（2026-11-03）

### 10.1 为什么没按原方案加 `status=2`

原建议是 `AVAILABLE(0) / RESERVED(2) / DELIVERED(1)`。落地时放弃了这个方案，原因是**影响面不成比例**：
`status=1` 在 `findByReservationKey`、`findOrderReservations`、`countUsed`、备份导出、`KamiBackupHandler`
等 10 余处被当作"已占用"使用，把预占改成 2 需要逐处判断"这里该不该包含预占"，任何一处漏改都会表现为
库存统计错乱或旧预占查不回来——这比要修的问题本身更危险。

实际采用**同状态 + 确认时间戳**：`status=1 && delivered_time IS NULL` 即"预占待确认"。
所有既有的 `status=1` 查询语义完全不变（预占与已交付都算占用），只新增了"能否区分"的能力。

### 10.2 改动清单

| 层 | 文件 | 改动 |
|---|---|---|
| 数据 | `db/migration/V3_25__kami_delivered_time.sql` | 新增 `delivered_time` 列；存量 `status=1` 按 `COALESCE(used_time, create_time)` 回填为已交付；新增 `idx_kami_item_pending` |
| 数据 | `sql/schema.sql` | 同步列定义与索引（新装库） |
| 实体 | `entity/XianyuKamiItem.java` | 新增 `deliveredTime` |
| 仓储 | `mapper/XianyuKamiItemMapper.java` | `markUsed/markUnused/markUnusedIfReserved` 显式清空交付时间；新增 `markDelivered`（条件更新、幂等）、`findStaleReservations`、`countPendingDelivery`；筛选支持 `pendingOnly` |
| 仓储 | `mapper/XianyuKamiUsageRecordMapper.java` | 新增 `findByItemId`、`deleteByItemId`（容忍使用记录缺失） |
| 语义 | `service/delivery/DeliveryReservation.java` | 新增 `commitAction`；新增 `hold()` 与 `commitWith()` / `rollbackAndCommitWith()`，把"确认交付 / 冻结待核对 / 释放"三者分开 |
| 语义 | `service/delivery/KamiDeliveryStrategy.java` | 成功 → 写交付确认；复用历史预占 → 只允许确认，永不允许释放 |
| 语义 | `service/impl/AutoDeliveryServiceImpl.java:604` | WS 通道 UNKNOWN 由 `commit()` 改为 `hold()`——原写法会把"未知"谎报成"已交付" |
| 巡检 | `service/delivery/KamiReservationSweeper.java`（新增） | 每 5 分钟扫描超 30 分钟未确认的预占；订单成功→补确认，订单失败→释放，其余→告警 |
| 服务 | `KamiConfigService(Impl)` | 新增 `confirmKamiDelivered`、`releaseStaleKamiReservation`、`confirmKamiItemDelivery`、`releaseKamiItemReservation` |
| 接口 | `controller/KamiConfigController.java` | 新增 `/item/confirm-delivery`、`/item/release-reservation`（均需 `kami:write`） |
| 展示 | `KamiItemRespDTO` / `KamiConfigRespDTO` | 新增 `deliveredTime`、`reservationState`、`pendingCount` |
| 前端 | `views/kami-config/index.vue`、`api/kami-config.ts` | 新增"待确认"徽标与筛选、行内"已发出 / 未发出"处置、配置卡片显示"N 待确认" |

### 10.3 安全边界（刻意保守的地方）

1. **无法判定时绝不自动释放**。订单待定或使用记录缺失时，只告警不回滚——自动释放可能把已经发给买家的卡再卖一次。
2. **已交付的卡不允许经"释放预占"退回**。`releaseKamiItemReservation` 对 `deliveredTime != null` 直接拒绝，提示改用重置并核实买家。
3. **升级时存量一律判为已交付**。迁移回填宁可高估，也不让巡检去动历史数据。
4. **确认写入失败不影响发货成功**。`confirmAction` 吞掉异常并记日志，由巡检按订单终态补记，避免"卡发出去了但接口报错"。

### 10.4 验证结果

- 编译：504 源文件 **BUILD SUCCESS**
- 定向测试：`DeliveryReservationTest`(5)、`KamiReservationSweeperTest`(5)、`KamiDeliveryStrategyTest`(6)、`KamiConfigServiceImplTest`(6) 全绿；连同 Sprint 0 的 9 个测试类共 **135 用例全绿**
- 全量回归：`mvnw test` → **534 用例，0 失败，3 跳过，BUILD SUCCESS**
- SQL 实测（临时 sqlite 库）：迁移可在旧表结构上执行，存量 `status=1` 被正确回填；`findStaleReservations` 只命中"占用但未确认"的记录；`markDelivered` 首次影响 1 行、重复执行 0 行（幂等）；`markUnused` 清空交付时间
- **未验证**：前端无 `node_modules`，未跑 `vue-tsc`；巡检未做并发压测

### 10.5 遗留与注意

1. 巡检告警粒度为**单张卡密**（key=`kami_reservation_stranded_{itemId}`）。大量滞留时会一次性产生多条告警，需要按实际运营规模考虑聚合。
2. 订单终态为"成功"才自动补确认；如果进程在"已发送但未回写订单 state"之间崩溃，该卡会停在待确认并告警，**需要人工确认一次**——这是无法从本地数据推断的边界。
3. `resetKamiItem`（重置）仍可把已交付的卡直接置回未使用，属于既有高危操作，未纳入本次改动，建议后续加二次校验或 ADMIN 限制。
4. P0-3 修复后，Sprint 0 引入的"UNKNOWN 导致库存缓慢泄漏"已被闭环：泄漏会被巡检在 30 分钟内收敛或显式告警。

---

## 十一、Sprint 2 修复记录：P0-5 按订单的发货租约（2026-11-03）

### 11.1 为什么没按原方案加 `delivery_phase` 列

原建议是给 `xianyu_goods_order` 加 `delivery_phase`，用条件 UPDATE 抢锁。落地时放弃，原因有二：

1. **抢锁失败时的语义没有落脚点**。条件 UPDATE 影响 0 行只说明"有人在发"，但调用方仍然需要一个返回值来表达"本次没执行"。若直接沿用 `FAILED`，调度器会打 ERROR 日志、前端会提示"发货失败"，等于把并发冲突谎报成业务失败——这正是 P0-4 那类"失败语义污染"的翻版。
2. **改表要配迁移、回填与备份兼容**。订单表是备份 12 模块之一，加列需要同步 `OrderBackupHandler`，而收益仅是"多实例也安全"——本项目是单机 SQLite 部署，这个收益暂时不存在。

最终采用**进程内租约 + 显式 `SKIPPED` 结果**：改表为零，语义清晰，且能在单机场景下做到完全互斥。多实例需求出现时，把 `DeliveryOrderLeaseManager` 换成数据库实现即可，调用方不用改。

### 11.2 改动清单

| 层 | 文件 | 改动 |
|---|---|---|
| 新增 | `service/delivery/DeliveryOrderLeaseManager.java` | 「账号+订单号」互斥租约：`ConcurrentHashMap.compute` 原子抢占；同线程可重入（计数）；跨线程关闭无效；悬挂超过 TTL（默认 600s）可被接管 |
| 新增 | `service/delivery/DeliveryOrderLease.java` | 可关闭句柄，重复 close 幂等；订单号缺失时返回 noop 租约 |
| 语义 | `service/ApiDeliveryResult.java` | 新增 `SKIPPED`：并发冲突，未执行，**不是失败** |
| 入口 | `service/impl/AutoDeliveryServiceImpl.java` | `executeDelivery` 持租约执行（原方法体抽为 `doExecuteDelivery`）；`manualDelivery` 同样持租约，冲突时提示"正在自动发货中" |
| 入口 | `service/impl/OrderServiceImpl.java` | `consignDummyDeliveryWithConfig` 持租约（原方法体抽为 `doConsignDummyDeliveryWithConfig`），冲突返回 `SKIPPED` |
| 兜底 | `service/impl/AutoDeliveryServiceImpl.java` | 发货前查 `countDeliveredByOtherRecord`：同一订单已被另一条记录发成功则跳过（事件换 pnmId 重推的场景） |
| 仓储 | `mapper/XianyuGoodsOrderMapper.java` | 新增 `countDeliveredByOtherRecord`（按条件计数，避免 `LIMIT 1` 取到错误行） |
| 调用方 | `service/ApiDeliveryScheduler.java` | `SKIPPED` → 记 INFO 并 `continue`，不计失败、不等待 |
| 调用方 | `controller/OrderController.java` | `SKIPPED` → 返回"该订单正在发货中，请稍后刷新，勿重复提交" |
| 测试 | `DeliveryOrderLeaseManagerTest`(7)、`OrderServiceImplDeliveryReservationTest`(+1)、`AutoDeliveryServiceImplTest`(+1) | 覆盖互斥、重入、跨线程关闭、TTL 接管、并发时零副作用 |

### 11.3 语义边界

1. **`SKIPPED` 必须与 `FAILED` 分开**：不写订单状态、不释放卡密、不告警、不重试。否则并发冲突会被统计成发货失败，把 P0-4 的坑重挖一遍。
2. **跳过发生在任何副作用之前**：租约在取卡、查订单详情、发消息之前获取，测试用 `verify(never())` 守住这条线。
3. **租约不覆盖"确认发货/自动评价"**：这些动作不占卡密、不对外发内容，不参与互斥，避免把锁粒度放大到整条订单生命周期。
4. **悬挂接管是有意的**：持有线程被 kill 时租约不会自然释放，TTL 到期后允许接管，避免一笔订单因一次崩溃永久卡死；接管时打 WARN 便于追查。

### 11.4 验证结果

- 编译：**BUILD SUCCESS**
- 全量回归：`mvnw test` → **543 用例，0 失败，3 跳过，BUILD SUCCESS**（较上一轮 +9，均为本次新增）
- 定向测试：租约 7 例、订单服务 15 例、发货服务 15 例、调度器 7 例全绿
- **未验证**：多实例并发（当前实现本身不支持）；租约与 `@Async` 线程池饱和叠加时的排队表现

### 11.5 遗留与注意

1. **单机有效**：租约是进程内内存态。若将来多开实例或多机部署，必须换成数据库/Redis 租约，否则互斥失效。
2. **被跳过的触发不会补发**：并发时后到的一方直接放弃。正常情况下先到者会写终态；若先到者异常退出且未写状态，订单会停在 `state=0`，由 API 兜底调度或人工接管——这是"宁可漏发一次让兜底补，也不要并发发两次"的取舍。
3. **重复记录仍会留痕**：事件换 pnmId 重推时，第二条记录会保持 `state=0` 且不发货，页面上表现为一条待处理记录，需要人工删除或核对。没有自动合并，是为了不伪造发货成功数。
4. **下一步建议**：P0-2（隔离默认拒绝）仍是最大的权限面缺口；之后是 P1-3 outbox 兜底扫描与 P1-4 任务租约 fencing。



---

## 十二、Sprint 3 修复记录：剩余 P0 与全部 P1（2026-11-03）

本轮目标是把剩余异常一次性清完。除 P1-4（租约 fencing，架构级）外，P0/P1 全部落地，
P2 只做"会造成真实业务损失"的部分。

### 12.1 P0 收尾

| 项 | 改动 | 关键文件 |
|---|---|---|
| P0-2 隔离 fail-open | `getVisibleAccountIds` 非 ADMIN 空范围返回空列表（默认拒绝）；`assertAccountVisible` 给出"未配置范围"的明确 403；切面新增字符串 id 解析，`{"accountId":"7"}` 不再被忽略，解析失败直接 403；同时修正容器遍历顺序（见 12.5 第三条），`Map`/`List`/数组/DTO 内的账号 ID 都会参与校验 | `rbac/service/RbacService.java`、`rbac/aspect/AccountScopeAspect.java` |
| P0-7 AI 无超时 | 主 ChatClient 也注入 `SimpleClientHttpRequestFactory`（connect 5s / read 35s，可配但有硬上限 30s/120s）+ `RetryTemplate maxAttempts(1)`。Spring AI 默认重试 10 次，叠在无超时读上是灾难性的 | `config/rag/DynamicAIChatClientManager.java` |
| P0-8 登录死循环 | 守卫失败改跳 `/login?reason=perm_unavailable`；缺权限时找"第一个可访问页面"，找不到才回登录页并提示；登录页见 reason 不再自动跳转；登录/注册成功后先拉权限再 `router.replace`，失败停留提示 | `router/index.ts`、`views/login/index.vue` |
| P0-1 残留：备份导出/导入只挡了明文凭据 | Sprint 0 只给 `includeSecrets=true` 加了 ADMIN 门禁，但**非管理员仍能导出整库数据**（全账号订单、聊天记录、卡密），也能导入覆盖全库——`backup:write` 是 OPERATOR 默认权限。现改为 `/backup/export`、`/backup/import` 统一要求 ADMIN，拒绝行为记 WARN；`backup:read`（列表/状态）保留给 OPERATOR | `controller/DataBackupController.java`、`common/ResultObject.java`（新增 `forbiddenWithMessage`） |

**升级注意（P0-2）**：这是行为变更。非管理员在管理员配置账号范围前，将看不到任何账号数据
（此前是看全部）。首位注册用户自动绑定 ADMIN，单机自用场景不受影响；多人协作场景需要
先到「权限管理 → 账号范围」授权一次。

### 12.2 P1 后端

| # | 改动 |
|---|---|
| 1 | 备份导入时 `ai_base_url` / `ai_embedding_base_url` 仅 ADMIN 可写，且必须通过 `AiEndpointValidator`；非管理员导入这两个键直接跳过并记 WARN |
| 2 | （Sprint 0 已做）幂等键强键优先 |
| 3 | 重投扫描改 `FAILED`（到期）+ `PENDING`（创建超 2 分钟）双查询；新增 `inFlightDeliveries` 防同一条记录被两轮扫描并发重发 |
| 5 | 新增 `reopenIfResolved`：同一 alertKey 复发时把 RESOLVED 重置为 ACTIVE 并清空抑制窗口；`delivery_failure` 改为 `delivery_failure_{accountId}` |
| 6 | `NotifyService.send` 改为返回 `SENT / FAILED / NO_CHANNEL`，删除共享可变字段 `lastSendFailed`；`NO_CHANNEL` 既不推进窗口也不计失败 |
| 7 | 发货成功后置 `delivered` 标志，收尾异常不再改写状态；`updateStateContentAndFailReason` 的 SQL 加 `WHERE #{state}=1 OR state IS NULL OR state!=1` 幂等守卫；自动确认发货单独 try/catch |
| 8 | 兜底发货加进程内退避：按「账号\|订单」记失败次数，指数退避 1 分钟起、上限 30 分钟，8 次后停止自动重试并告警 |
| 9 | `executeAutoReply` 增加 `ReplyOutcome`（SUCCESS/SKIPPED/UNKNOWN/FAILED），worker 只在 FAILED 时返回 false 触发重试；UNKNOWN 记成功避免重复回复；唯一索引冲突时若原记录是 `-1` 则 `reopenFailed` 重开一次并继续发送 |
| 10 | 延时消息缓存改为 `compute` 内复制新 List + `List.copyOf` 传参（并发序列化不再 CME），单会话上限 50 条；新增 `clearPendingMessages`，worker 执行完在 finally 清理 |
| 11 | 向量库保存改「写 .tmp → ATOMIC_MOVE」，全程持写锁；新增 `mutate(store -> ...)` 让 add/delete 与持久化在同一把锁内；文件损坏时重命名为 `.corrupt-<ts>` 并 `log.error`，不再静默以空库运行 |
| 12 | `cleanTempFiles`：目录也加 1 小时年龄判断，浏览器存活时跳过 `*-profile-*`；`Files.list` 改为 try-with-resources（原实现泄漏目录句柄） |
| 13 | 卡密库存告警邮件移到 `afterCommit` 执行，避免 SMTP 超时期间持有 SQLite 写锁 |
| 14 | `deleteConfig` / `addKamiItem` / `batchImportKamiItems` / `deleteKamiItem` / `resetKamiItem` / `confirmKamiItemDelivery` / `releaseKamiItemReservation` 七个事务方法不再吞异常，改为日志 + 抛出 `BusinessException`（由 GlobalExceptionHandler 统一返回） |

**顺带修的**：`ResetKamiItem` 重置已交付卡密时记录操作人与订单号（高危操作留痕）；
`JwtUtil` 生成密钥文件后收紧为 `rw-------`（Windows 忽略）；`buyNum` 缺失时按 1 件发货
但打 WARN 提示可能少发。

### 12.3 P1 前端

| # | 改动 |
|---|---|
| 15 | AI 流式请求加 `AbortController` + 120s 超时，`onUnmounted` 中断；中断/超时给出"请求已中断或超时"而不是一直转圈；`chatWithAI` 支持传 signal |
| 16 | 商品同步轮询：加 in-flight 去重、连续失败 5 次即停止并复位 `syncing/refreshing`、提示刷新页面 |
| 17 | 消息静默轮询在非第 1 页时只更新 `total`，不再把第 1 页数据并进第 N 页 |
| 18 | 连接详情页在桌面端不再自己起 10s 轮询（桌面渲染的 `<DesktopDetail>` 已有一路），只在移动端轮询，并按 `isMobile` 变化启停 |

**未做 18 的根治**：两套 1200+ 行实现仍并存。合并需要重写移动端页面，风险大于收益，留到 UI 阶段二。

**复核时补的两处**（不新增条目，属 12.3 的收尾）：

- `resolveLandingPath(state)` 从守卫里抽出来并导出，登录页也用它算落地页。原实现登录成功后固定
  `router.replace('/dashboard')`，若账号零权限，守卫会把导航打回 `/login?reason=no_permission`，
  而此时登录页已挂载、`onMounted` 不再执行 —— 用户看到的是一张没有任何提示的空白登录页。
  现在登录页在跳转前就知道"没有可用页面"，直接停在登录页给出可读提示。
- 消息静默轮询固定请求第 1 页（`requestPage = silent ? 1 : currentPage`）。P1-17 只改了
  "第 N 页不并列表"，但请求本身仍按 `currentPage` 发，拿回的是第 N 页数据却只读 `total`，
  等于每次轮询都白跑一趟且判断不出第 1 页是否来了新消息。

### 12.4 未做（明确放弃，不是遗漏）

| 项 | 原因 |
|---|---|
| P1-4 任务租约 fencing（`lease_token` + 续期独立调度器） | 需要改 job 表结构 + 续期线程模型，属架构级改造；当前 SQLite 单机、worker=4，触发概率低。建议与"多实例支持"一起做 |
| P2 登录限流改账号维度、CallerRunsPolicy 改拒绝策略 | 前者要改接口签名并同步前端提示；后者换成丢弃策略会让消息事件静默丢失，比"读线程被占用"更糟。都需要单独设计，不能顺手改 |
| P2/P3 巨型类拆分、`request<T=any>` 收紧、13 份状态色映射合并 | 纯重构，无行为收益，放在 UI 阶段二/三 |

### 12.5 验证结果

- 编译：**BUILD SUCCESS**
- 全量回归：`mvnw test` → **561 用例，0 失败，3 跳过，BUILD SUCCESS**
  （其中新增 `AccountScopeAspectTest` 11 个用例、`DataBackupControllerAdminOnlyTest` 5 个用例）
- 修复过程中被测试挡下的三处：
  - `NotifyService` 改返回类型导致 4 个断言失效（已同步更新）；
  - 新增日志里的变量名 `response` 命中敏感日志扫描规则（已改名 `readSeconds`）；
  - **`AccountScopeAspect` 的 `isSimpleValue` 把 `java.` 前缀一律当叶子值**，
    而 `Map.of`/`List.of`/`ArrayList` 的实现类同样以 `java.` 开头 —— 容器判断排在
    叶子判断之后，导致请求体（`Map`/`List`/DTO）里的 `accountId` 完全不被校验，
    P0-2 修复后仍有一条绕过路径。已改为容器优先展开、`java.` 前缀仅用于阻止对
    JDK 内部类型做反射，并补上对象数组展开。
- **前端验证降级**：仓库无 `node_modules`。尝试 `npm ci` 被环境的安全删除守卫拦下
  （bulk delete 需确认），改 `npm install` 后安装到 203MB 处长时间卡住、`.bin` 未生成，
  `vue-tsc` 无法启动。改用已落地的 `typescript` 对 7 个改动文件（`router/index.ts`、
  `login/index.vue`、`useMessageManager.ts`、`useGoodsManager.ts`、`useAutoReply.ts`、
  `api/ai.ts`、`ConnectionDetail.vue`）做**语法级**校验，全部通过；**类型校验当时未做**，
  `node_modules` 处于半装状态。
  **后续补记（Sprint 4）**：`npm install --ignore-scripts --prefer-offline` 补齐了 `vue-tsc`，
  `npm run type-check`（`vue-tsc --build`）已通过，见 13.5。上面 561 用例是 Sprint 3 当时的快照；
  最新全量为 567 用例，同样见 13.5。

### 12.6 升级前必读

1. **非管理员默认看不到账号数据**（P0-2），需要管理员先配范围。
2. **数据备份的导出/导入只有 ADMIN 能做**（P0-1）。OPERATOR 仍可在备份页看到列表与状态，
   但点导出/恢复会被拒绝——这不是 bug，是权限收敛：备份是整库快照，按账号裁剪会产生
   半库备份、恢复时破坏关联。单机自用（首位用户即 ADMIN）不受影响。
   **Sprint 4 兼容性变化**：`/backup/export` 现在也返回附件形式的原始备份 JSON
   （与 `/export/stream` 等价），不再是 `ResultObject<BackupExportRespDTO>` 包裹的字符串。
   当前前端已改用流式下载；若有外部脚本直接调这个端点，需要同步改为按附件接收。
3. **AI 调用不再自动重试**：`maxAttempts(1)`。上游偶发 5xx 时用户会直接看到失败，
   由前端/用户重试，而不是后台默默重试 10 次把 worker 占满。
4. **告警无渠道时不再推进抑制窗口**：没配任何通知渠道时，告警会持续 `ACTIVE` 且
   `send_attempts` 不增长，配置渠道后立即能收到。
5. **兜底发货失败 8 次后停手**：需要人工处理，日志会有 `API兜底发货连续失败8次` 的 ERROR。
6. **备份导出改为附件下载**（Sprint 4）：设置页不再把整份 JSON 读进 JS 内存，
   详见第十三章。

---

## 十三、Sprint 4 修复记录：两条高风险 P2（2026-11-03）

用户从 12.4 列出的未做项里点名要收掉的两条：**备份导出 OOM** 与 **Webhook 队头阻塞**。
这两条都属于"平时不显、真出事就是事故"的类型，且都不需要改表结构。

### 13.1 改动文件

| 文件 | 作用 |
|---|---|
| `backup/DataBackupHandler.java` | 新增 `supportsStreamingExport()` 开关 + `exportJson(generator, context)` 默认实现，小模块仍走原有 `exportData()` |
| `backup/handler/ChatLogsBackupHandler.java` | 去掉 N+1、改主键游标分页、覆写流式导出 |
| `mapper/XianyuChatMessageMapper.java` | 新增 `selectPageAfterId(lastId, limit)`，按主键游标翻页 |
| `service/DataBackupService.java` / `impl/DataBackupServiceImpl.java` | 新增 `exportDataToStream(OutputStream, reqBO)`，逐字段手写 JSON；旧 `exportData()` 复用同一路径保证内容一致 |
| `controller/DataBackupController.java` | 新增 `/backup/export/stream`；`/backup/export` 收编为同语义流式；加 `X-Backup-Export: ok` 标记头 |
| `service/DataBackupScheduler.java` | 定时备份改为流式直写临时文件，不再经 `String` |
| `vue-code/src/api/backup.ts` | 新增 `downloadBackupStream()`，裸 axios + `responseType: blob`，按标记头区分成功流与错误 JSON |
| `vue-code/src/views/settings/index.vue` / `settings.css` | 设置页改流式下载；去掉伪造的百分比进度，换不确定态进度条 |
| `webhook/service/WebhookService.java` | 内联重试改跨轮重投、池 2→4 / 队列 100→200、累计次数上限与指数退避 |

### 13.2 备份导出：从"三份完整副本"到"单页 + 单模块"

旧路径的峰值内存约为数据量的 3 倍：`ChatLogsBackupHandler` 把**全表实体**读进 `List`，
再构造一份等大的 `List<Map>`，`DataBackupServiceImpl` 又 `writeValueAsString` 出一份
完整 JSON 字符串；同时每条消息都调一次 `accountMapper.selectById` 取 `unb`（N+1），
十万条就是十万次查询，导出看起来像卡死。

改造：

- `unb` 一次预取成 `Map<Long,String>`，消息按 `id > lastId ORDER BY id LIMIT 500`
  游标翻页；每页写完即释放，峰值只跟单页有关。
- `DataBackupServiceImpl.exportDataToStream()` 用 `JsonGenerator` 逐字段写，
  模块写完即可回收，不再先拼整棵 `Map`。
- `JsonGenerator.Feature.AUTO_CLOSE_TARGET` 关掉，输出流由 HTTP 层/调用方负责关闭。
- **两条实际运行路径都切了流式**：手动导出（前端）与**每日自动备份**（`DataBackupScheduler`）。
  后者原来最危险——`exportData()` 拼出整份字符串再 `Files.writeString`，天天都会跑。
  现在直接写临时文件，并保留 `Files.size(temp) == 0` 守卫：空文件/中途异常都不会
  `move` 到正式文件名，顺带堵住"坏文件顶掉可用备份"里的空/半截文件场景。
- `/backup/export` 与 `/export/stream` 共用 `buildStreamingBackup()`，ADMIN 门禁在
  **开始写流之前**完成（流一旦开始写，状态码已发出，再拒绝只会留下半截文件）。
- 正常备份和异常响应的 Content-Type 都是 `application/json`，无法靠 `blob.type` 区分，
  所以服务端给成功的备份流加 `X-Backup-Export: ok`；前端没有该标记就按错误体解析，
  不会把一段错误 JSON 存成"备份文件"。
- 前端：`downloadBackupStream()` 用 `timeout: 0`（大文件不能被默认超时掐断）、
  手动带 token、延迟 60s 再 `revokeObjectURL`（立即撤销会让大文件下载中断）；
  流式响应没有 Content-Length，去掉了原来"循环 sleep 假装涨到 100%"的进度条，
  换成不确定态动画。

### 13.3 Webhook：慢端点不再占死发送线程

旧实现里 `sendWebhook` 在**同一个 worker 线程内**串行重试 `retryCount` 次，每次之间
`Thread.sleep(1000 * attempt)`；池子只有 2 个线程。一个慢端点就能把两个线程都吃掉，
后面所有事件（包括发给健康端点的）全部排队。

改造：

- 发送池 `2 → 4`、有界队列 `100 → 200`；队列打满时任务转 `FAILED` 交给重投兜底，
  不无限堆积。
- **内联重试改为跨轮重投**：一次 `sendWebhook` 只发 1 次，失败后写回
  `attempts + 1` 与 `next_attempt_at`，由 60s 周期的 outbox 扫描重投，退避期间
  不占线程。总尝试上限仍是 hook 的 `retryCount`（`clampRetry` 封顶 10）。
- `backoffSeconds(attempts)` 指数退避（封顶 `BACKOFF_MAX_SECONDS`）；累计达到上限时
  把 `next_attempt_at` 置 `NULL`，扫描的 `isNotNull` 条件自然跳过，不需要新增状态位。
- 沿用 Sprint 3 已有的 `inFlightDeliveries` 去重与"陈旧 PENDING 重扫"，
  避免并发重发与进程退出导致的事件丢失。
- `X-Webhook-Attempt` 头固定为 `1`（每次都是独立投递轮次，不再是循环内的第 N 次）。

### 13.4 已知限制 / 明确未做

| 项 | 说明 |
|---|---|
| 流式写中途抛异常 | HTTP 200 与标记头已发出，客户端会拿到**截断的 JSON**。导入端解析会失败并报错（用户能发现），但备份文件本身没有完整性校验字段。要做需加尾部校验值 + 导入端校验，属另一轮改造 |
| `/backup/export` 兼容性 | 返回体从 `ResultObject<...>` 变成附件，旧版前端/外部脚本需同步；见 12.6 第 2 条 |
| 备份导入 | 仍单事务持写锁数十秒、无导入前快照（第五章 P2 未动） |
| Webhook "先查后改" | 告警/Webhook 多处非原子抢占，重复投递的根治未做 |
| 其余 P2/P3 | 登录限流账号维度、`CallerRunsPolicy` 改造、巨型类拆分等仍按 12.4 放弃 |

### 13.5 验证结果

- 后端编译：**BUILD SUCCESS**
- 后端全量回归：`mvnw test` → **567 用例，0 失败，3 跳过，BUILD SUCCESS**
  （Sprint 3 的 561 + 6：`DataBackupSchedulerTest` 4 例〔原 2 例改为流式 mock，新增
  空流不覆盖、流异常不留半截文件 2 例〕、`DataBackupControllerAdminOnlyTest` 6 例
  〔原 5 例适配新返回类型，新增 `/export/stream` 非 ADMIN 被拒 1 例〕）
- 被测试挡下的一处：mock `exportDataToStream` 时把 `OutputStream` 取成了
  `getArgument(1)`（实际是第 0 个参数），`ClassCastException` 让备份写完前就中断，
  表现为"trim 后剩 3 个文件而不是 2 个"。已修正。
- 前端：`npm install --ignore-scripts --prefer-offline` 补齐依赖后，
  **`npm run type-check`（`vue-tsc --build`）通过，EXIT=0**；再用
  `npx vue-tsc --build --force` 强制全量重跑（排除增量缓存）同样 EXIT=0。
  另用 `typescript` 对 8 个改动文件做语法级校验，全部 OK。
  Sprint 3 遗留的"类型校验未做"至此闭环。
- `vite build` 未跑（`--ignore-scripts` 会让 esbuild 的 postinstall 二进制缺失），
  不影响类型结论；下次正常 `npm ci` 会重建完整依赖树。