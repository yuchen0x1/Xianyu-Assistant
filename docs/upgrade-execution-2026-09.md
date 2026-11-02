# 升级执行记录（2026-09：P0 可靠性闭环、秘密治理与备份升级）

> 记录“新版本全面升级”阶段 2 的改动批次、测试命令与真实结果。本文档只记录本批次新增内容；更早的升级历史见本文档同目录 `upgrade-execution-2026-08.md`。

## 目标（阶段 2）

把 Webhook、通知、告警和备份从“有类和页面”补成可观测、可恢复的业务闭环：

1. Webhook 由真实业务事件触发，投递历史可查询（outbox）。
2. 告警持久化、去重、确认/静默/恢复，失败可重试。
3. 敏感设置与 Webhook secret 加密落库，备份默认不泄密。
4. 备份从“7 个 handler”补齐为真正全量（订单、聊天、通知、任务、监控、发布历史等）。

## 1. Webhook outbox 与业务事件接线

- `webhook_delivery` 持久化投递记录：`dispatch` 先写 PENDING → 异步投递 → 更新 SUCCESS/FAILED/UNKNOWN、attempts、http_status、last_error、next_attempt_at（指数退避，封顶 300s）。
- 业务事件已接入：`delivery.completed/failed`（AutoDeliveryServiceImpl）、`order.created`（ChatMessageEventAutoDeliveryListener）、`connection.lost/restored`（WebSocketServiceImpl）、`alert.triggered`（AlertEventService）、`kami.low_stock`（KamiConfigServiceImpl）。
- 出站加固：连接前再次解析并校验 IP（防 DNS 重绑定），`HttpClient.Redirect.NEVER` 禁止重定向。
- 幂等键 `webhookId:event:businessKey`；投递查询返回脱敏 `payloadSummary`（只列字段名，不回显值）。
- 相关文件：`webhook/service/WebhookService.java`、`webhook/entity/WebhookDelivery.java`、`webhook/mapper/WebhookDeliveryMapper.java`、`webhook/dto/WebhookDeliveryVO.java`。

## 2. 告警持久化与监控中心

- `alert_event` 持久化告警：唯一 `alert_key`、`INSERT OR IGNORE` 原子创建 + 条件性 `bumpIfSendable` 抢占抑制窗口，只有发送成功才推进窗口；失败只累计 `send_attempts`，等待下个窗口重试。
- 状态机：ACTIVE / ACKED / SILENCED / RESOLVED；`ACK` 可重复（幂等）、`SILENCE` 需要 ACTIVE、`RESOLVE` 释放收到。
- 健康检查接入：`DeadLetterAlertMonitor`（死信堆积）、`SystemHealthIndicator` 等。
- Monitoring API：`/api/monitoring/overview`、`/api/monitoring/alerts`、`POST /api/monitoring/alerts`（ack/silence/unsilence/resolve），前端 `/monitoring` 页面。
- `monitoring:read/write` 权限码已随 V3_17 写入，ADMIN/OPERATOR/VIEWER 角色自动授权。
- 相关文件：`monitor/entity/AlertEvent.java`、`monitor/mapper/AlertEventMapper.java`、`monitor/service/AlertEventService.java`、`monitor/AlertService.java`、`monitor/DeadLetterAlertMonitor.java`、`monitor/BusinessMetricsService.java`、`controller/MonitoringController.java`、`vue-code/src/views/monitoring/`。

## 3. 通道独立与可观测通知

- `NotifyService.send()` 先持久化站内广播通知，再独立遍历各渠道：匹配且发送失败或异常时递增 `failedChannels`，并记录 `lastSendFailed()`；单渠道失败不阻塞其他渠道。
- 新增 `NotifyServiceChannelTest` 固化“渠道相互独立、false 计为失败”语义。

## 4. 敏感秘密治理

- `XianyuSysSetting` 的 SENSITIVE_KEYS（AI Key、SMTP 密码等）写入时 `CredentialCipher.encrypt`（AES-GCM `AESGCM:` 前缀），读取时解密；响应保持脱敏。
- `WebhookConfig.secret` 使用 `EncryptedStringTypeHandler` 落库；响应只返回 `hasSecret`；更新时 `********` 掩码保留原密钥。
- 备份导出默认设置：`DataBackupHandler.exportSecrets(context)`，只有显式 `includeSecrets=true` 才导出明文 Cookie/Token；`AccountBackupHandler` 默认导出 null。

## 5. 备份 handler 补齐（全量备份）

新增 5 个 handler，与现有 7 个 handler 一起构成可恢复的完整备份集：

| handler | moduleKey | 说明 |
| --- | --- | --- |
| `OrdersBackupHandler` | `orders` | 商品订单；按 (accountId, orderId) 幂等导入，更新 state/failReason |
| `ChatLogsBackupHandler` | `chatLogs` | 聊天记录；导出脱敏（不含完整消息体/买家ID/链接），按 pnmId 幂等 |
| `NotificationsBackupHandler` | `notifications` | 站内通知；追加式导入（不可覆盖，保留轨迹） |
| `JobsBackupHandler` | `jobs` | 任务定义/执行/死信 + Webhook 配置（不含 secret）+ 告警事件；统一脱敏 |
| `PublishHistoryBackupHandler` | `publishHistory` | 发布历史；按 id 幂等导入 |

导出最小原则：聊天记录不上传 `completeMsg/reminderUrl/senderUserId`，Job/Webhook/告警导出不含 `secret`、执行结果原始 JSON 与告警正文中的敏感值。

`DataBackupServiceImpl`：`BACKUP_CAPABILITIES` 增加 `completeOrderHistory`、`completeChatLogs`、`completeNotifications`、`jobsAndMonitoring`、`publishHistory`；`IMPORT_ORDER` 扩展为 account → deliveryBlockRule → goods → kami → autoReply → autoDelivery → systemSetting → orders → chatLogs → notifications → publishHistory → jobs。

## 6. 数据库迁移与 schema.sql 一致性

- `V3_15__notification_user_scope.sql`：通知表增加 `recipient_user_id`、`account_id`、`broadcast` + 已读回执表。
- `V3_16__job_p0_idempotency_lease.sql`：清理空幂等键行、唯一 `idempotency_key` 索引、`lease_until` RUNNING lease。
- `V3_17__monitoring_webhook_outbox.sql`：合并告警事件 + Webhook 投递记录 + 监控权限码（旧拆分文件 `V3_17__alert_event_monitoring.sql`、`V3_17__webhook_delivery.sql` 已删除）。
- `schema.sql` 补齐缺失表：`xianyu_publish_history`、`xianyu_schedule_task`、`xianyu_goods_price_history`、`webhook_config`、`job_definition/job_execution/job_dead_letter/delivery_compensation`、`account_risk_event/account_automation_pause`；`xianyu_goods_order` 增加 `idempotency_key/delivery_phase/unknown_since`。新库直接建全量表，旧库由 `V3_1..V3_17` 迁移升级。
- 已验证：`schema.sql` 在新 SQLite 库整体执行成功（36 张表），重复执行幂等。

## 7. 测试

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试 | `.\mvnw.cmd test` | 429 tests / 0 failures / 0 errors / 2 skipped |
| 前端单测 | `npm test -- --run`（vue-code） | 6 files / 23 tests 通过 |
| 前端类型 | `npm run type-check`（vue-code） | 通过 |
| 前端构建 | `npm run build`（vue-code） | 通过，`src/main/resources/static` 已重新生成 |

新增/更新的关键测试：

- `BusinessBackupHandlersTest`（10）：订单导入幂等、聊天导出脱敏、通知账号映射、发布历史 round-trip、Webhook 导出不含 secret、告警状态可恢复。
- `SchemaMigratorV3_17Test`（5）、`AlertEventMapperSqlTest`、`AlertEventServiceTest`、`MonitoringControllerTest`。
- `WebhookServiceTest`（11）：SSRF/DNS 重绑定、掩码保留、outbox 状态流转。
- `NotifyServiceChannelTest`：渠道独立与失败计数。
- `SysSettingServiceImplTest`（+9）：敏感 key 加密落库与解密读取。

## 阶段 3.1：自动评价与求好评

- 新增 `V3_18__review_workflow.sql`，创建 `review_rule` 与 `review_record`，以 `(account_id, order_id)` 保证评价任务幂等，并新增 `review:read/review:write` 权限。
- 新增 ReviewRule/ReviewRecord 实体与 MyBatis-Plus mapper。
- `ReviewService` 支持规则保存、按订单调度、人工确认、取消、补发；延迟任务使用 JobEngine 的 `REVIEW_PENDING` 类型。
- 当前评价 Worker 不伪造平台成功结果：平台自动评价接口未接入时标记 `MANUAL_REQUIRED`，保留人工处理入口。
- 新增 `/api/reviews` API 与前端 `/reviews` 页面，支持规则配置、记录观察、确认/重试/取消。
- `ReviewServiceTest` 覆盖幂等调度、人工确认和“未接入平台能力不伪造成功”语义。

## 8. 回退说明

- 全部为增量功能，未破坏既有表结构；旧库通过迁移升级，备份包 `version` 恒为 `2.0` 兼容导入。
- 备份默认不含明文自定义字段；如需恢复 Cookie/Token，导出时显式选择“包含敏感信息”。
- 回退只需还原对应源码文件并重新构建；已应用迁移不建议回退（如需回退配合数据库备份还原，见 `docs/升级方案-v3.1-2026-08-02.md`）。

## 阶段 3.2（部分）：智能议价与通知测试推送（2026-09-04）

### 智能议价

- 策略模型：决策由代码计算（`BargainPlanService`），AI 只负责措辞。商品级配置三要素——**保底价**（必填）、**让价步长**（选填，缺省按 (售价−保底价)/最大轮数 均分）、**最大轮数**（默认 3）。
- 决策矩阵：出价 ≥ 保底价 → ACCEPT；还价 = max(保底价, 售价 − 步长×轮次)，买家出价达到本轮可让价 → 直接 ACCEPT；轮次耗尽 → FIRM 坚持底价；无售价锚点时按 保底价 + 步长×剩余轮数 递减。
- 意图识别：`BargainDetector` 纯正则（砍价关键词 + 货币出价/裸数字出价/让价差额三类句式），零额外 AI 调用，误判防护（"3天发货"不触发）。
- 会话跟踪：`bargain_session` 按 (账号, sId) 幂等，轮次 +1/出价/回复落库；空闲 48 小时或切换商品自动重置。
- 集成点：`AIReplyStrategy` → `AIService.chatByRAGWithPolicy`（新增策略注入段），人工确认模式与自动发送链路均透明生效；AI 不可用/失败时用确定性话术模板兜底，策略不落空。
- 顺带修复：AI 错误占位文案（"AI服务暂未配置"等）此前可能被当作回复内容发给买家，现在按失败处理。

### 通知测试推送

- `POST /api/setting/testNotify`（settings:write）向单渠道发送测试消息；`NotifyService.testChannel` 校验渠道已配置后直发。
- 设置页六个渠道（企业微信/钉钉/Server酱/Bark/Telegram/ntfy）标题行新增"发送测试"按钮，替代原"监控中心 → 立即巡检"间接触发方式。

### 迁移与验证

- `V3_22__bargain_strategy.sql`：`xianyu_goods_config` 增加 `bargain_on/bargain_floor_price/bargain_step_price/bargain_max_rounds`；新建 `bargain_session`（UNIQUE (xianyu_account_id, s_id)）；`schema.sql` 同步补齐。
- 新增测试 26 个：`BargainDetectorTest`（10）、`BargainPlanServiceTest`（12）、`SchemaMigratorV3_22Test`（4，内容级校验）。
- 真实迁移器实测：以本地 V3_9 老库副本执行 `SchemaMigrator.migrate()`，V3_15→V3_22 一次性平滑应用，议价字段与会话表就位。

### 修复：全新空库首次启动失败（历史缺陷）

- 现象与根因：旧初始化顺序为"先迁移、后建表"，而多个迁移语句依赖 schema.sql 才创建的基础表（V3_3 写入 `xianyu_sys_setting` 等），全新空库上迁移在 V3_3 即中断 → schema.sql 建表步骤被跳过 → 新装部署无表可用。经真实迁移器在空库上复现确认，自引入版本化迁移起即存在。
- 修复：`DatabaseInitListener` 调整为"解析 schema.sql → 先建缺失基础表 → 应用版本迁移 → 补字段/索引/触发器"。新库先获得全量表结构，迁移补记版本时对 schema.sql 预建的重复列容错跳过（该容错本就存在）；旧库升级路径不变（基础表已存在时建表步骤自动跳过）。
- 验证：新增 `DatabaseInitListenerFreshDbTest`（2）以真实组件在空库上跑完整初始化流程——22 个迁移全部记录、基础表/议价表/RBAC 种子齐备、二次启动幂等；全量 460 个测试通过（仅余 2 个与本改动无关的存量失败）。

## 阶段 3.2（续）：在线一键更新（v3.3.21，2026-09-04）

### 功能与流程

- 更新弹窗新增「立即在线更新」（服务端检测到以独立 JAR 运行才显示；触发需 `settings:write`）：`SelfUpdateService` 后台执行 下载新版 JAR（边下边算 SHA-256，进度实时上报）→ 与 Release 随附 `.jar.sha256` 比对（不符即删除放弃）→ 旧包备份 `.bak` → 原子替换（失败回滚）→ 按重启策略重启；前端轮询进度，重启后轮询 `/api/system/health` 直到版本号变为目标版本并自动刷新页面。
- 下载通道：JAR、SHA-256 校验文件和签名文件统一来自 GitHub Release；应用内更新默认访问公开 GitHub Release tags API，并要求 SHA-256 校验与官方 Ed25519 签名。支持 `app.self-update.jar-base-url` 镜像前缀覆盖（仍强制校验）。
- 安全约束：下载仅允许 HTTPS + GitHub 官方 API/资产主机白名单；手动逐跳跟随重定向并逐跳校验，跨主机剥离 Authorization 防令牌泄漏；`new`/`part` 临时文件失败即清理。

### 重启策略与部署适配

- `app.self-update.restart-mode`：`exit`（默认；退出交 systemd `Restart=always` / Docker / 守护循环拉起）、`manual`（只替换，提示人工重启）。历史 `command` 模式已废弃，不再执行配置驱动的 shell 命令。
- install.sh 由裸 `nohup java` 改为守护循环（进程退出 2 秒自动拉起），pid 文件记录循环 PID，停止命令改为 `pkill -P` + `kill`。

### 测试

- 新增 `SelfUpdateServiceTest`（12）：JAR 定位（-jar 参数/环境变量/开发模式不误判）、校验文件解析（标准/星号前缀/非法输入）、下载主机白名单（官方主机放行、HTTP/陌生主机/携带用户信息拒绝）、状态机守卫（非 JAR 不可用、目标版本不高于当前拒绝、更新中拒绝重复触发与重启）、`VersionUtils` 归一化比较。
- 修复存量失败 2 个（`AutoReplyDelayServiceImplTest`）：JobEngine `createJob` 已升级为 8 参签名（maxRetries/priority/rearmOnSuccess），测试仍验证旧 5 参重载导致"想要但未调用"；更新测试至 8 参并修正原生类型匹配器（anyInt/anyBoolean）。全量测试首次全绿（462 通过、0 失败）。

## 待办（阶段 3.2 余项）

售后、发布工作台、客户运营与报表按既定计划在后续阶段实现。