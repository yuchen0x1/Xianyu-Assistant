# 升级执行记录（2026-08）

> 目标：将"功能堆叠的个人工具"升级为成熟的"闲鱼卖家运营控制台"。
> 本文档记录基线、改动批次、测试命令、真实结果与回退说明。所有测试结果均为本机实测。

## 0. 基线（2026-08-06 建立）

### 0.1 仓库状态
- Git 分支：`master`，HEAD `faf017b`。
- 用户已有未提交改动（**必须保留，勿覆盖**）：
  - `src/main/java/com/feijimiao/xianyuassistant/config/SchemaMigrator.java`：SQLite `ALTER TABLE ADD COLUMN` 重复列幂等容错。
  - `src/main/java/com/feijimiao/xianyuassistant/job/service/JobEngine.java`：任务 CAS 领取（PENDING/FAILED → RUNNING）。
  - 未跟踪：`src/test/java/com/feijimiao/xianyuassistant/job/service/JobEngineTest.java`（2 条测试）。
- 上述改动正好覆盖既有分析文档指出的两个 P0 风险（迁移幂等、任务重复执行），保留并纳入验证。

### 0.2 测试命令与结果（全部通过）
| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试 | `.\mvnw.cmd test`（JAVA_HOME=Temurin-21） | 0 failures / 0 errors / 2 skipped（日志下载测试） |
| 前端类型 | `npm run type-check`（vue-code） | 通过 |
| 前端单测 | `npm test`（Vitest） | 5 files / 20 tests 通过 |
| 前端构建 | `npm run build:spring` | 通过，已重新生成 `src/main/resources/static` |

### 0.3 环境
- 应用目录：`XianYuAssistant`；服务端口 `12400`，默认只绑 `127.0.0.1`。
- `java` 不在 PATH；使用 `JAVA_HOME=C:/Users/LENMO\AppData\Local\Programs\Temurin-21\jdk-21.0.11+10`。
- 后端配置：`src/main/resources/application.yaml`。
- 构建产物：`src/main/resources/static`（只由构建生成，禁止手改）。

### 0.4 已确认 P0 风险清单（基线核查）
1. **RBAC 未落地业务接口**：`@RequirePermission` 只用在 `RbacController`，其余业务 Controller 仅登录校验。
2. **敏感配置明文泄露**：`/api/setting/list`、`/api/setting/get` 返回全量明文（AI Key、SMTP 密码、钉钉密钥等）。
3. **Webhook 泄露与 SSRF**：`WebhookController` 直接返回含明文 `secret` 的实体；`test/sendWebhook` 对任意 URL 发请求，无内网/环回防护。
4. **Job Engine 无真实 Worker**：`JobWorkerRegistry` 未注册任何 Worker；`DELIVERY_REVIEW` 任务会以"未注册的 Worker"反复重试直至死信。
5. 迁移幂等 + Job CAS：已由用户改动修复，保留并有测试覆盖。
6. **（冒烟新发现）SchemaMigrator 切分 bug**：`splitStatements` 按"行尾分号"切分，遇到 `CREATE TRIGGER ... BEGIN ... END;`（V3_6/7/8 均有）把块内分号误切 → `SQLITE_ERROR: incomplete input` → V3_6 回滚。实证：真实库 `schema_version` 只到 `3_5`，`sys_role`/`sys_permission` 等 RBAC 表从未落库，权限层实际不可用。

## 1. 批次 1（P0 安全垂直切片）

### 1.1 范围
- Webhook：secret 掩码 + SSRF 防护 + 权限注解 + 前端页面收敛。
- 系统设置：敏感 key 掩码 + 留空保持原值 + `clearSecret` 单独清空 + 权限注解 + 前端适配。
- 权限：新增 `webhook/jobs/notification/backup/system` 权限码（V3_9 迁移），业务 Controller 补齐 `@RequirePermission`。
- 任务引擎：未注册 Worker 快速失败进死信；注册 `DELIVERY_REVIEW` 真实核对 Worker（订单 state=1 自动确认；结果未知/风控拦截如实标记需人工，不伪装成功）。
- SchemaMigrator：`splitStatements` 改为 BEGIN/END 块感知，触发器内分号不再误切（修复真实库迁移停在 V3_5 的 P0）。

### 1.2 改动文件（见 Git diff，批次完成时更新）
- 后端：`V3_9__rbac_more_permissions.sql`、`webhook/dto/WebhookVO.java`、`webhook/service/WebhookService.java`、`webhook/WebhookController.java`、`service/impl/SysSettingServiceImpl.java`、`controller/SysSettingController.java`、`controller/dto/GetSettingRespDTO.java`、`controller/dto/SaveSettingReqDTO.java`、`service/bo/GetSettingRespBO.java`、`service/bo/SaveSettingReqBO.java`、`job/service/JobEngine.java`、`job/config/JobWorkerRegistry.java`、若干业务 Controller 权限注解。
- 前端：`src/api/webhooks.ts`、`src/api/setting.ts`、`src/views/webhooks/index.vue`（掩码编辑/测试回填/徽标）、`src/views/settings/index.vue`（敏感键掩码、代理池防覆盖、通知渠道 11 键接通）。
- 测试：`WebhookServiceTest`、`SysSettingServiceImplTest`、`JobEngineTest` 增补。
- 迁移修复：`config/SchemaMigrator.java`（`splitStatements` BEGIN/END 感知，保留用户已有 ALTER 幂等逻辑不动）、新增 `config/SchemaMigratorSplitStatementsTest`（4 条）。

### 1.3 批次测试结果（真实实测，2026-08-06 复核）
| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试 | `.\mvnw.cmd test`（JAVA_HOME=Temurin-21） | **324 tests / 0 failures / 0 errors / 2 skipped** |
| 新增后端测试 | `WebhookServiceTest`（7）、`SysSettingServiceImplTest`（6）、`DeliveryCompensationServiceTest`（5）、`SchemaMigratorSplitStatementsTest`（4） | 全部通过 |
| 保留用户测试 | `JobEngineTest`（2） | 通过（未改动用户文件） |
| 前端类型 | `npm run type-check` | 通过 |
| 前端单测 | `npm test`（Vitest） | 5 files / 20 tests 通过 |
| 前端构建 | `npm run build:spring` | 通过，已重新生成 `src/main/resources/static` |

**本批新增/修复点（前端掩码适配）：**
- Webhook 页面：编辑不再预填密钥、占位提示"留空则保持原密钥"、测试请求带 `id` 由后端回填已存密钥签名、`hasSecret` 徽标、移除页面 emoji。
- 系统设置掩码适配：`ai_api_key`、`ai_embedding_api_key`、`email_smtp_password`、`notify_dingtalk_secret`、`notify_serverchan_sendkey` 的加载改为"已配置留空保持"，保存校验放宽，成功后可重新标记已配置。
- **修复 P0 回归**：`proxy_list` 被整体掩码后，旧代码 `JSON.parse('********')` 会失败并把空列表写回清空代理。现改为：掩码时不展示明文、保存时空列表不覆盖（`preserveProxyList`）、编辑后覆盖需二次确认。
- **修复静默丢弃**：Bark / Telegram / ntfy 配置此前只在界面展示、从不加载/保存（保存提示"成功"但实际不落库）。已接通 `loadNotifyConfig` / `handleSaveNotifyConfig` 的 11 个键，敏感键（`notify_bark_device_key`、`notify_telegram_bot_token`、`notify_ntfy_token`）同样走掩码+留空保持。
- 已知限制：掩码后的密钥暂未提供"显式清空"按钮（后端 `clearSecret=true` 已支持，留待批次 5 补 UI）。


### 1.4 回退说明
- 权限注解如导致非管理员用户被拒，可按用户反馈移除对应 Controller 注解或调整角色授权；SQLite 数据不受影响。
- V3_9 为纯增量权限种子（`INSERT OR IGNORE`），可安全重复执行；不删除任何行。
- 掩码逻辑仅影响读取响应；如需恢复明文展示，删除 `SysSettingServiceImpl.SENSITIVE_KEYS` 与 `WebhookService.toVO` 掩码即可，但会重新引入泄露风险。

### 1.5 迁移切分器修复与冒烟复验（新 P0，2026-08-06）
- **问题**：真实库 `dbdata/xianyu_assistant.db` 的 `schema_version` 停在 `3_5`，V3_6~V3_9 从未落库；启动日志曾报 `SQLITE_ERROR: incomplete input`。原因是 `splitStatements` 把 `CREATE TRIGGER ... BEGIN ... END;` 块内分号误判为语句边界。
- **修复**：按行识别 `BEGIN` / `END[;]`（大小写不敏感；迁移脚本中仅触发器块使用，无 `BEGIN TRANSACTION` 变体），块内分号不切分。保留用户已有的 ALTER TABLE 幂等容错不动。
- **测试**：新增 `SchemaMigratorSplitStatementsTest` 4 条（普通分号、触发器内分号、多触发器、块内注释），全部通过；全量后端 324 测试通过。
- **冒烟复验**（`spring-boot:run` + 真实 SQLite）：启动日志显示 V3_6/V3_7/V3_8/V3_9 依次执行成功；`/actuator/health` 200；登录页 200。直接查询数据库：`schema_version` 含 3_1~3_9 共 9 条；`sys_role`（3 行）、`sys_permission`（38 行）、`sys_role_permission`（ADMIN 38 条）、`sys_user_role`（既有用户已自动挂 ADMIN）全部存在；V3_9 新增权限码 `webhook:read`、`webhook:write`、`jobs:read`、`jobs:admin`、`system:read` 已落库。验证后服务已停止，端口 12400 已释放。
- **影响**：修复前本批新增的 `@RequirePermission` 在真实库上会因权限表缺失而异常/拒绝；修复后权限链路真正可用，未破坏单机既有数据（迁移为纯增量，不删行）。


### 1.6 任务引擎 apply() 占位符修复（新发现 P0，2026-08-07 真实库复验）
- **问题**：真实库（SQLite）启动后任务调度线程每 5 秒报 `任务轮询异常`，堆栈为 `MybatisPlusException: Please check the syntax correctness! sql not contains: "{0}"`；`PENDING/FAILED` 任务永远无法被轮询领取，`DELIVERY_REVIEW` 等任务实际从不执行。
- **根因**：批次 1 引入的 `LambdaQueryWrapper.apply("next_run_time IS NULL OR next_run_time <= ?", now)` 使用了 JDBC 风格 `?` 占位符；MyBatis-Plus 3.5.x 的 `apply()` 必须使用 `{0}` 命名占位符，否则构建 wrapper 时即抛异常。纯 Mockito 单测不触发真实 SQL 绑定，因此未被批次 1 的测试发现（潜伏 bug）。
- **修复**：`JobEngine.dueJobQuery` 改为字符串列 `QueryWrapper` + `apply("next_run_time IS NULL OR next_run_time <= {0}", now)`，与该类 `UpdateWrapper` 字符串列风格一致；轮询异常改为打印完整堆栈便于定位。
- **回归测试**：`JobEngineTest.dueJobQueryUsesMybatisPlusApplyPlaceholderNotQuestionMark` 断言 wrapper 可正常构建、参数确实绑定（`paramNameValuePairs` 含 now）且 SQL 片段不含裸 `?`；`JobEngineTest` 由 2 条增至 5 条。
- **验证**：`.\mvnw.cmd test`（JAVA_HOME=Temurin-21）全量 **347 tests / 0 failures / 0 errors / 2 skipped**；全库扫描无其他 `apply`/`last` 携带 `?` 占位符的写法。
- **真实库冒烟**（2026-08-07，`java -jar XianYuAssistant-3.3.0.jar` + `dbdata/xianyu_assistant.db`）：插入 `SMOKE_APPLY_FIX`（PENDING、`next_run_time=NULL`、`max_retries=0`）后，调度线程首个轮询周期即领取任务并执行完整链路——`job_execution` 记录 FAILED（`未注册的 Worker`）、`job_definition` 转 `DEAD_LETTER`、`job_dead_letter` 落库；日志无任何 `任务轮询异常`。冒烟数据已清理，服务已停止，端口 12400 已释放。
## 2. 批次 2（P1 收尾：图标/渐变/圆角 token 收敛）

### 2.1 范围
- 清理由上个批次遗留的 emoji 图标与文案 emoji（主导航已在上批完成，本批收敛到全部清零）。
- 全库扫描并收敛残留渐变：面板 1px 高光线、开关滑块、步骤编号装饰渐变改为设计 token；仅保留 token 化骨架屏 shimmer（功能性加载指示）。
- 对话框/弹窗圆角与硬编码色收敛到 `--radius-*` / `--bg-*` / `--text-*` / `--brand-*` token（连接详情、更新弹窗、自动发货/回复配置、登录二维码等）。
- 移除 `clamp()` 视口单位字号（dashboard 指标数字改 `var(--text-2xl)`）。

### 2.2 改动文件（本批，Vue 源码）
- `src/views/connection/components/ConnectionDetailDialog.vue`：12 个 emoji 换 SVG 图标；废弃 `<template #footer>` 改为卡内 `.modal-footer`（底部按钮回到卡内）；渐变/大圆角/硬编码色 token 化。
- `src/views/dashboard/index.vue`、`src/views/dashboard/dashboard.css`：6 处渐变→实色（绑定条/排名徽章）；指标字号 `clamp()`→`var(--text-2xl)`。
- `src/views/auto-delivery/auto-delivery.css`、`src/views/auto-reply/auto-reply.css`：面板高光线/开关滑块渐变→token；对话框圆角 20px→`var(--radius-sm)`。
- `src/components/layout/UpdateDialog.vue`：硬编码颜色与圆角全部 token 化。
- `src/views/goods/components/GoodsTable.vue`（`✎`→IconEdit）、`src/views/auto-delivery/index.vue`（`🔑`→IconKey）、`src/views/auto-reply/index.vue`（`💬`→IconChat + 补空态样式）。
- `src/views/accounts/components/QRLoginDialog.vue`：`✕`→IconClose + import + SVG 尺寸规则。
- `src/views/settings/index.vue`：3 处 `✓`→IconCheck；checkbox 选中态改 `var(--brand-600)`；步骤编号紫色渐变→`var(--brand-600)`；开关滑块渐变→`#fff`；内容面板高光线→`var(--border-1)`。
- `src/views/messages/messages.css`、`src/views/goods/goods.css`、`src/views/orders/orders.css`、`src/views/operation-log/operation-log.css`：面板 1px 高光线渐变→`var(--border-1)`（暗色模式不再发白刺眼）；messages 开关滑块→`#fff`。
- `src/views/auto-delivery/useAutoDelivery.ts`、`src/views/connection/useConnectionManager.ts`：文案内 emoji（⚠️/💡）改中文前缀，保持面向卖家短文案。

### 2.3 视觉批次说明（非视觉 QA）
- 涉及页面：dashboard、messages、orders、connection（详情/登录二维码）、goods（表格/详情）、auto-delivery、auto-reply、settings、operation-log、UpdateDialog。
- 断点：本批为 token/资源收敛，未改布局断点；响应式结构由既有 AppLayout 侧栏/抽屉承担（P2 逐页复核）。
- 设计 token：`--brand-600/700`、`--success-600`、`--warning-600`、`--danger-500`、`--bg-elevated/muted/hover`、`--border-1`、`--text-1/2/3`、`--radius-sm/pill`、`--duration-fast`、`--ease-standard`。
- 交互状态：开关开/关、checkbox 选中/未选中、hover、关闭按钮 aria-label、SVG `aria-hidden`。
- 自动化检查：全库 emoji 扫描 0 残留；渐变扫描仅剩 2 处 token 化骨架屏 shimmer；`font-size` 无 vw/vh/clamp 视口单位。
- 仍需人工视觉确认：暗色模式下各面板高光线与开关滑块观感；连接详情弹窗底部按钮入卡后的间距；设置页 checkbox 图标尺寸。

### 2.4 批次测试结果（真实实测，2026-08-06）
| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型 | `npm run type-check`（vue-code） | 通过（多次，含本批最后改动后） |
| 前端单测 | `npm test`（Vitest） | 5 files / 20 tests 通过 |
| 前端构建 | `npm run build:spring` | 通过，`src/main/resources/static` 已重新生成（构建产物，未手改） |

- 构建期修复：`settings/index.vue` checkbox-tick 样式补丁多出 1 个 `}` 导致 CSS 语法错误（vite 报 `Unexpected }`），已删除多余括号后构建通过——验证了"每批先 type-check/构建再继续"的节奏。
- 遗留（推迟到 P2 各页组）：部分页面仍有 >8px 硬编码圆角（消息气泡、按钮、图片、toggle 等 12~22px），按页面分组重构时逐页收敛；`backdrop-filter: blur` 玻璃效果（pending-orders 等）留待 P2 决策。

### 2.5 回退说明
- 本批全部为前端样式/图标/文案修改，不触碰数据与后端接口；回退只需还原 `vue-code/src` 下对应文件并重新 `npm run build:spring`。
- 未改 `src/main/resources/static` 中任何文件内容（由构建整体重新生成）。

## 3. 批次 3（P2：页面重构：玻璃/圆角收敛 + 空态失败态补齐）

### 3.1 范围与决策
- 覆盖页面：Dashboard / 消息 / 待发货（pending-orders）/ 连接 / 商品 / 发布 / 订单 7 个页面组，外加外壳组件（AppLayout 移动端侧栏遮罩、UpdateDialog、CommandPalette）。
- **玻璃效果决策**：全站移除 `backdrop-filter`（含 `-webkit-` 前缀），表面一律改为实色 token 面（`--bg-elevated` / `--bg-muted` / `--bg-hover`）；模态/抽屉遮罩统一为深色 scrim `rgba(15, 23, 42, 0.45)`；图片浮层上的圆形按钮、图片遮罩（`rgba(0,0,0,.5~.75)`）与品牌按钮白字保留原设计。
- **圆角收敛**：>8px 硬编码圆角（10/12/13/14/15/16/20px）→ `var(--radius-sm)`；6/7px → `var(--radius-xs)`；100/999px 与开关滑块 22px → `var(--radius-pill)`；消息气泡 `14px 14px 14px 4px` → `var(--radius-sm) var(--radius-sm) var(--radius-sm) var(--radius-xs)`。
- **颜色收敛**（玻璃改实色后保证暗色可读）：`#1c1c1e`→`--text-1`、`#0A84FF/#007aff`→`--brand-600`、iOS 红/绿/橙→`--danger-500/--success-500/--warning-500`、`rgba(10,132,255,..)` 主按钮/蓝色 tint→`--brand-600/--bg-active`、`rgba(60,60,67,.12)` 悬停→`--bg-hover`、滚动条→`--border-2` 等。
- 保留项：`box-shadow` 阴影值、图片上方白字/遮罩、dark 专用微调（`[data-theme="dark"]` 内 rgba(255,255,255,...)）。

### 3.2 状态补齐
- `pending-orders/index.vue`：新增加载中（spinner）、失败态（含重试按钮，区分账号加载失败与订单加载失败）、空态图标（IconShoppingBag）；此前只有一行纯文本空态，且失败会静默清空列表。
- `publish/index.vue`：`⌫` emoji → IconTrash；历史区加载/空态已存在（纯文本，保留）。
- 消息/连接/商品/订单/Dashboard 已具备加载与空态（连接卡片、会话列表、消息上下文、商品/订单表格均有 loading/empty），本批仅统一视觉。

### 3.3 主要改动文件（Vue 源码，27 个）
- pending-orders：`index.vue`（全量收敛 + 状态补齐）。
- publish：`index.vue`（卡片/输入圆角、玻璃移除、图标）。
- messages：`index.vue`（移动端头部玻璃→实色）、`messages.css`（`--d-*` 液态玻璃定义原地收敛为 token，含 radius/shadow/surface 定义）、`ContextDialog.vue`、`MessageList.vue`、`GoodsSidebar.vue`、`useMessageManager.ts`。
- connection：`ConnectionDetail.vue`、`connection.css`（无改动）、`components/ConnectionDetail.vue`、`ConnectionDetailDialog.vue`、`CredentialModal.vue`、`CaptchaGuideDialog.vue`、`ManualUpdateCookieDialog.vue`、`ManualUpdateCookieModal.vue`、`ManualUpdateTokenDialog.vue`、`MobileCredentialModal.vue`、`QRUpdateDialog.vue`、`RefreshCookieDialog.vue`。
- goods：`goods.css`、`components/GoodsDetail.vue`、`GoodsTable.vue`、`GoodsDetailDialog.vue`、`GoodsEditorDialog.vue`。
- orders：`orders.css`、`components/OrderTable.vue`、`useOrderManager.ts`。
- 外壳：`components/layout/AppLayout.vue`（侧栏遮罩 blur 移除）、`UpdateDialog.vue`、`CommandPalette.vue`。

### 3.4 批次测试结果（真实实测，2026-08-07）
| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型 | `npm run type-check`（vue-code） | 通过（多次） |
| 前端单测 | `npm test`（Vitest） | 5 files / 20 tests 通过 |
| 前端构建 | `npm run build:spring` | 通过，`src/main/resources/static` 已重新生成 |
| 后端测试 | `.mvnw.cmd test` | **324 tests / 0 failures / 0 errors / 2 skipped** |

- **环境变化**：原文档记录的 `JAVA_HOME=Temurin-21（AppData\Local\Programs\Temurin-21）` 已不存在；系统仅有 Android Studio JBR 25（Mockito MockMaker 初始化失败，237 个环境性 Error）与 JDK 17。已临时下载 Temurin 21.0.12 到 `%TEMP%\temurin21\jdk-21.0.12+8` 完成验证（0 代码失败）。后续如需长期保留，建议重装 Temurin 21 并更新本节。
- 构建期修复：批次中曾因移除 `backdrop-filter` 的正则未带 `-webkit-` 前缀，把 `-webkit-backdrop-filter:...;` 误切成 `-webkit-` 残行导致 CSS 语法错误（vite 报 `Unknown word display`），已清理残行后构建通过。
- 自动化检查：批次 3 目标页面 `backdrop-filter` 0 残留、>8px 硬编码圆角 0 残留、emoji 0 残留。

### 3.5 回退说明
- 本批全部为前端样式/图标/状态修改，不触碰数据与后端接口；回退只需还原 `vue-code/src` 下对应文件并重新 `npm run build:spring`。
- 遮罩统一为 `rgba(15, 23, 42, 0.45)`，如偏好原 `rgba(0,0,0,.2)` 可在 `src/views` 下全局替换。
- 未手改 `src/main/resources/static`（由构建整体重新生成）。



## 4. 后续批次（待办）
- 批次 4（P3）：性能（懒加载、请求取消、轮询按可见性、分页、SQLite 索引）。
- 批次 5（P4）：质量、安全与交付收尾（掩码显式清空按钮、键盘可达性、对比度、最终构建与冒烟）。
