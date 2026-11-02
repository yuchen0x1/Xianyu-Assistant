# 闲鱼助手功能缺口迭代审计

审计日期：2026-07-16

审计范围：当前工作区 `XianYuAssistant` 的 Java 后端、Vue 前端、SQLite schema、运行配置和公开可访问的同赛道开源项目/成熟基础设施项目资料。本报告不修改业务代码；“缺失”表示代码库中未找到可供用户操作或稳定运行的完整闭环，而非单纯没有某个类名。

## 结论摘要

当前项目是**单机、本地优先的虚拟商品运营工具**，而不是空壳：账号连接、商品同步、会话、关键词/AI 回复、卡密、自动发货、订单核对、邮件告警、备份和操作日志都已有端到端实现。最应补的不是再造自动回复，而是让已有自动化在多用户、进程重启、任务积压、故障告警和外部协作场景下仍可运营。

优先补齐顺序：`RBAC 与账号隔离` -> `持久化任务中心与幂等重试` -> `健康检查、指标与告警` -> `凭证/密钥加密` -> `Webhook/API 集成`。商品发布、自动评价、采集选品属于业务扩展，排在上述运行底座之后。

## 1. 已实现功能清单

### 完整功能（代码、接口、UI 或运行闭环均可确认）

| 模块 | 已实现能力 | 代码证据 |
| --- | --- | --- |
| 身份与本地登录 | 首次注册、登录、JWT 会话、改密、BCrypt 密码、登录令牌持久化 | `controller/LoginController.java`、`service/impl/AuthServiceImpl.java`、`config/WebMvcConfig.java`、`schema.sql` 的 `sys_user`/`sys_login_token` |
| 账号与凭证 | 多闲鱼账号、扫码/手工添加、Cookie/Token 更新、账号详情与删除 | `controller/AccountController.java`、`controller/QRLoginController.java`、`vue-code/src/views/accounts`、`views/connection` |
| 实时连接 | WebSocket 启停、心跳、Token 刷新、凭证刷新、断线重连、滑块提示 | `service/impl/WebSocketServiceImpl.java`、`TokenRefreshServiceImpl.java`、`WebSocketTokenServiceImpl.java`、`application.yaml` 的 `websocket.*` |
| 商品管理 | 商品同步、详情异步同步、SKU、商品级自动化开关与固定资料 | `controller/ItemController.java`、`GoodsSkuController.java`、`ItemDetailSyncServiceImpl.java`、`views/goods` |
| 会话工作台 | 会话/上下文查询、文字和图片发送、按商品联动筛选、人工接管 | `controller/MsgController.java`、`service/ManualModeServiceImpl.java`、`views/messages` |
| 自动回复 | 精确/模糊/正则关键词、AI 润色、RAG 知识库、商品固定资料、延迟回复、人工接管取消、回复记录 | `controller/KeywordReplyController.java`、`AIChatController.java`、`service/reply/*`、`views/auto-reply` |
| 自动发货 | 文本/图片/卡密/自定义模式、SKU 配置、库存预占-提交/回滚、失败/未知状态、手工发货、自动确认发货 | `AutoDeliveryServiceImpl.java`、`AutoDeliveryConfigController.java`、`DeliveryStrategyResolver`、`views/auto-delivery` |
| 订单与卡密 | 订单同步和详情补充、待处理订单、发货记录、卡密批量导入/导出/库存预警/使用记录 | `OrderController.java`、`PendingOrderPollService.java`、`KamiConfigController.java`、`schema.sql` 的订单和卡密表 |
| 数据面板 | 日度统计、趋势、实时收入/销售额、短时本地缓存、前端定时刷新 | `DataPanelController.java`、`CacheService.java`、`views/dashboard/index.vue` |
| 通知 | SMTP 配置、测试邮件、连接断开、Cookie 过期、滑块、库存不足、发货失败邮件 | `EmailNotifyServiceImpl.java`、`SysSettingController.java`、`views/settings/index.vue` |
| 运维与数据 | 操作日志查询/清理，模块化数据备份、导入恢复，Docker/JAR 部署和本地持久卷 | `OperationLogController.java`、`DataBackupController.java`、`backup/*`、`Dockerfile`、`README.md` |

### 已实现但尚不构成生产级闭环的功能

| 功能 | 已有部分 | 未闭环原因 |
| --- | --- | --- |
| 异步执行 | `AsyncConfig` 有 5/10 线程、100 队列；WebSocket 有内存重连任务；延时回复和令牌刷新有定时任务 | 队列、延时任务、重试状态未持久化；`AutoDeliveryServiceImpl` 和 `QRLoginServiceImpl` 仍直接 `new Thread`。进程重启后无法恢复待执行任务，也没有统一任务视图。 |
| 风控 | 有人机延迟、滑块检测、连接/令牌重试和 `RiskControlService` | 没有账号级限速策略、风险事件中心、暂停/恢复审批或跨账号配额。 |
| 操作日志 | 有异步写入、查询和清理 UI | 没有结构化指标、调用链、日志检索/导出、告警规则或任务级关联 ID。 |
| 数据备份 | 支持模块选择、导出和导入 | 没有计划备份、保留策略、恢复演练校验、异地目标或备份成功/失败告警。 |
| 单一登录 | 有 JWT、令牌表、登录限流与本地密钥持久化 | 表模型仅有用户状态，没有角色、权限、账号归属或资源授权；实际是“多账号单操作者”。 |
| 邮件通知 | 关键失败事件和 SMTP 测试已实现 | 仅邮件单通道，未提供消息平台/Webhook、模板管理、去重聚合、告警确认或告警历史。 |

## 2. 外部检索、关键词与来源

### 自动生成的检索关键词

`闲鱼 自动回复`、`xianyu assistant`、`闲鱼 多账号 自动发货`、`闲鱼 商品发布 自动评价`、`闲鱼 选品 采集 分销`、`任务调度 幂等 重试`、`Prometheus Grafana OpenTelemetry`、`Keycloak Casbin RBAC`、`Webhook 插件扩展`。

### 采信来源

1. [zhinianboke/xianyu-auto-reply](https://github.com/zhinianboke/xianyu-auto-reply)：GitHub 搜索结果中同赛道高星开源项目（检索时 5,765 stars）。README 明确列出多账号、自动回复/发货、商品发布、自动评价、采集分销、通知风控；并拆分 WebSocket、Scheduler 和 Web 服务，使用 Redis、APScheduler、健康检查。
2. [personal82555/xianyu-super-assistant](https://github.com/personal82555/xianyu-super-assistant)：公开同赛道项目。README 列出 HTTP 回调、多用户权限、实时日志、QQ/钉钉/企业微信/Telegram/Email/Webhook 通知、健康检查、订单详情抓取、自动免邮。
3. [IAMLZY2018/XianYuAssistant](https://github.com/IAMLZY2018/XianYuAssistant)：本项目上游公开仓库。用于核实当前主营功能的赛道定位，不用于把上游未实现能力当作竞品基准。
4. [Quartz Scheduler](https://github.com/quartz-scheduler/quartz)、[Temporal](https://github.com/temporalio/temporal)、[RabbitMQ](https://github.com/rabbitmq/rabbitmq-server)：成熟调度、可恢复工作流与消息队列项目，用于任务执行/并发板块的行业实现参照。
5. [Prometheus](https://github.com/prometheus/prometheus)、[Grafana](https://github.com/grafana/grafana)、[OpenTelemetry Java](https://github.com/open-telemetry/opentelemetry-java)、[Apache SkyWalking](https://github.com/apache/skywalking)：成熟指标、仪表板、Trace/APM 项目，用于日志监控板块。
6. [Keycloak](https://github.com/keycloak/keycloak)、[Apache Casbin](https://github.com/casbin/casbin)：成熟身份访问管理与 RBAC/ABAC 授权项目，用于权限安全板块。

来源均于 2026-07-16 通过 GitHub REST API 获取仓库元数据和 README。本文不以搜索摘要作为事实依据；每个矩阵项均可回溯到以上 README/公开仓库描述或当前代码。

## 3. 行业标准功能矩阵与逐项比对

说明：`已具备`表示已看到当前实现；`部分具备`表示仅覆盖单机/单通道/非持久化子集；`缺失`表示本代码和 UI 中未发现对应闭环。外部参考缩写：`XY-Auto` = zhinianboke/xianyu-auto-reply，`XY-Super` = personal82555/xianyu-super-assistant，`Infra` = 上述成熟基础设施来源。

| 板块 | 外部可核验能力 | 外部依据 | 本项目状态 | 差距判断 |
| --- | --- | --- | --- | --- |
| 业务功能 | 多账号、消息、关键词/AI、自动发货、订单、卡密 | XY-Auto、XY-Super | 已具备 | 核心虚拟商品闭环齐全 |
| 业务功能 | 商品素材库、单品/批量发布、发布规则与发布日志 | XY-Auto | 缺失 | 没有发布/草稿/素材/地址/发布任务的模型、接口或页面 |
| 业务功能 | 自动评价、求小红花、订单状态跟踪 | XY-Auto | 部分具备 | 有订单同步和状态，缺自动评价/评价任务 |
| 业务功能 | 自动免邮、订单详情浏览器抓取 | XY-Super | 部分具备 | 有订单详情 API 补充；无免邮规则/执行记录 |
| 业务功能 | 商品采集、货源、选品规则、分销/结算 | XY-Auto | 缺失 | 无采集、选品、供应商或利润数据模型 |
| 后台管理 | 多用户角色（管理员/普通用户）、账号范围授权 | XY-Super；Keycloak/Casbin | 缺失 | `sys_user` 无角色/归属字段，拦截器只认证用户，不做授权 |
| 后台管理 | 实时日志流、可筛选任务/操作历史 | XY-Super | 部分具备 | 有操作日志和业务记录；无实时流、统一任务历史/导出 |
| 后台管理 | HTTP 回调/外部业务系统连接 | XY-Super | 缺失 | 未找到出站 Webhook、签名、订阅配置或投递记录 |
| 任务调度 | 专用 Scheduler、任务分层、计划任务 | XY-Auto | 部分具备 | Spring `@Scheduled` 和内存 executor 可运行；无统一调度服务/任务实体 |
| 任务调度 | 可恢复工作流、状态机、重试/补偿历史 | Temporal、Quartz | 缺失 | 无任务表、执行租约、死信/补偿任务、失败重放 UI |
| 并发处理 | 队列削峰、消费者隔离、失败重投 | RabbitMQ；XY-Auto 的 Redis/多服务架构 | 缺失 | 仅本进程线程池和 SQLite；高峰时 CallerRuns 会将压力回推到业务线程 |
| 日志监控 | 健康检查 | XY-Auto、XY-Super | 缺失 | 未找到 `/health`/Actuator 健康端点 |
| 日志监控 | 指标、仪表板、日志/Trace 关联 | Prometheus、Grafana、OpenTelemetry、SkyWalking | 缺失 | 无 Micrometer/Prometheus/OTel 依赖、指标端点或 trace ID |
| 日志监控 | 多渠道异常通知和告警去重 | XY-Super | 部分具备 | SMTP 邮件完整；缺 IM/Webhook、聚合/确认/静默 |
| 权限安全 | 密码哈希、JWT、登录会话管理 | XY-Super；Keycloak | 已具备 | BCrypt、JWT、令牌表、本地持久化密钥已实现 |
| 权限安全 | RBAC/ABAC、资源级授权 | Keycloak、Casbin；XY-Super | 缺失 | 没有角色权限表、注解/策略和账号资源过滤 |
| 权限安全 | 凭证/密钥静态加密和轮换 | Keycloak 的身份凭证管理为参考 | 缺失 | `xianyu_cookie.cookie_text` 和系统设置为普通文本存储；无 KMS/主密钥轮换设计 |
| 权限安全 | 外网部署的 HTTPS、CORS 白名单、审计 | XY-Auto、XY-Super | 部分具备 | 文档提醒限地址/强密码；默认本地绑定。没有强制 TLS、细粒度 CORS/审计不可篡改策略 |
| 扩展插件 | 可插拔事件/通知/发货渠道、扩展契约 | XY-Super 的 HTTP 回调；Backstage 的插件框架为参考 | 缺失 | 无 SPI、插件注册、生命周期、权限隔离或版本兼容机制 |

## 4. 功能补齐开发清单

### 刚需必补（P0）

| 项目 | 缺失功能与目标 | 开发内容 | 主要代码改造点 | 新增配置/数据 |
| --- | --- | --- | --- | --- |
| P0-1 | 多用户 RBAC 与账号数据隔离 | 引入用户、角色、权限、账号授权四层模型；所有账号/商品/订单/卡密/消息查询按授权范围过滤；前端按权限隐藏和禁用操作；保留最后一个管理员保护。 | 新增 `security` 包和 `@RequirePermission`；扩展 `WebMvcConfig` 的认证拦截器；改造所有 Controller/Service 查询入口；`views/accounts`、导航、设置页新增用户/角色管理。 | 表：`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`sys_user_account_scope`；`XIANYU_ASSISTANT_BOOTSTRAP_ADMIN_*`；权限码清单。 |
| P0-2 | 持久化任务中心 | 将自动回复延迟、自动发货、自动确认、订单轮询、令牌刷新等统一为持久化任务；任务具备幂等键、状态机、重试次数、下次运行时间、执行日志、人工重试/取消。 | 新增 `task` 模块（任务仓储、Worker、锁/租约、策略）；替换 `new Thread`；`AutoReplyDelayServiceImpl`、`AutoDeliveryServiceImpl`、`PendingOrderPollService`、`TokenRefreshServiceImpl` 改为提交任务；新增任务中心页面/API。 | 表：`job_definition`、`job_execution`、`job_attempt`、`job_dead_letter`；`XIANYU_ASSISTANT_JOBS_*`（worker 数、批量、租约、退避、保留期）。单机先用 SQLite lease，后续可切 Quartz/Redis/RabbitMQ。 |
| P0-3 | 任务幂等与发货补偿 | 对订单发货、卡密预占、确认发货建立明确的幂等状态机；结果未知自动生成核对任务，避免人工遗漏和重复发货。 | 扩展 `XianyuGoodsOrder`/Mapper；从 `AutoDeliveryServiceImpl` 抽出 `DeliveryWorkflowService`；将 `DeliveryReservation` 的提交/回滚与任务 attempt 绑定；订单页增加“核对完成/安全重试/放弃”动作。 | 表字段：`idempotency_key`、`delivery_phase`、`external_request_id`、`unknown_since`、`resolved_by`、`resolved_at`；`delivery.unknown-review-sla`、`delivery.retry-policy`。 |
| P0-4 | 健康、指标和主动告警 | 提供存活/就绪检查及应用、WebSocket、任务、发货、卡密、外部 API 指标；仪表板展示失败率、积压、重连和账号健康；按阈值发出告警。 | 引入 Spring Boot Actuator、Micrometer Prometheus、OTel Java；在 WebSocket、任务 Worker、发货和卡密服务埋点；新增 `/actuator/health`、`/actuator/prometheus`；新增监控页和告警规则。 | `management.*`、`OTEL_*`、`METRICS_ENABLED`、阈值（任务积压、失败率、连接断开、库存余量）；可选 Prometheus/Grafana Compose 配置。 |
| P0-5 | 闲鱼 Cookie、Token、AI/SMTP 密钥加密与轮换 | 对数据库敏感字段采用信封加密；主密钥来自环境变量/文件，支持版本标记、在线重加密和启动前校验；日志不输出明文。 | 新增 `security/crypto`、MyBatis TypeHandler 或专用 SecretService；改造 `XianyuCookie`、系统设置读取/写入、备份导出导入和日志脱敏；增加迁移命令。 | `XIANYU_ASSISTANT_MASTER_KEY`、`XIANYU_ASSISTANT_MASTER_KEY_FILE`、`CRYPTO_ACTIVE_KEY_VERSION`、轮换策略；字段加 `enc:vN:` 格式或独立密文列。 |
| P0-6 | 自动化运行权限与熔断 | 每账号设置速率、并发上限、冷却、异常暂停和恢复审批；风险或连续失败时停止新任务并通知。 | 扩展 `RiskControlServiceImpl`；任务提交前执行配额校验；连接/发货失败由统一策略更新账号自动化状态；前端增加账号健康与“暂停原因/恢复”界面。 | 表：`account_automation_policy`、`account_risk_event`；`risk.*`（分钟请求、并发、连续失败、冷却、自动恢复）。 |

### 体验优化（P1）

| 项目 | 缺失功能与目标 | 开发内容 | 主要代码改造点 | 新增配置/数据 |
| --- | --- | --- | --- | --- |
| P1-1 | Webhook 与开放 API | 对 `订单已付/发货成功失败/库存不足/账号异常/任务失败` 提供事件订阅；支持签名、重投、投递记录和 API Key。 | 新增 `integration` 模块、事件 outbox、Webhook Dispatcher、API Key 过滤器；业务服务写入事件；后台新增订阅管理/投递日志。 | 表：`webhook_endpoint`、`webhook_subscription`、`webhook_delivery`、`api_client`；`WEBHOOK_*`（超时、重试、签名算法、域名白名单）。 |
| P1-2 | 多渠道通知和告警治理 | 在已实现邮件基础上接入钉钉、企业微信、Telegram、通用 Webhook；告警按指纹去重、聚合、静默、确认。 | 将 `EmailNotifyService` 抽象为 `NotificationChannel`；新增通道实现和 `AlertService`；在 P0 指标/风险/任务事件上统一调用。 | 表：`notification_channel`、`alert_rule`、`alert_incident`、`alert_silence`；各渠道 Token/Webhook（加密存储）。 |
| P1-3 | 统一任务/业务审计检索与导出 | 合并操作日志、任务 attempt、发货/库存变动、权限操作为可筛选审计流；CSV/JSON 导出；关联 ID 可定位到订单/会话。 | 统一 `AuditEvent`；在 P0 任务中心和现有服务写入；扩展 `OperationLogController`；新增前端高级筛选、导出和详情抽屉。 | 表：`audit_event`；`audit.retention-days`、`audit.export-max-rows`。 |
| P1-4 | 计划备份与恢复验证 | 给已有备份增加计划、保留、校验、恢复前预检、恢复后一致性验证及失败告警。 | 扩展 `DataBackupServiceImpl`、备份 handler；通过任务中心调度；新增备份计划/历史页。 | 表：`backup_plan`、`backup_run`；`backup.storage-path`、`backup.retention`、`backup.cron`、可选 S3/OSS 配置。 |
| P1-5 | 商品发布与素材库 | 素材、地址、草稿、单品/批量发布、定时发布、发布日志和失败重试。该项直接对齐 XY-Auto 的商品发布能力。 | 新增 `publishing` 模块及 Playwright 流程；前端新增素材库/发布计划/日志；发布执行接入任务中心和风控。 | 表：`media_asset`、`address_book`、`listing_draft`、`publish_plan`、`publish_record`；`BROWSER_*`、`publish.*`。 |
| P1-6 | 自动评价和售后规则 | 定义订单完成触发、评价模板、延时、黑名单/白名单、评价记录和人工审核；不与发货直接耦合。 | 新增 `review` 模块、订单状态订阅、任务定义与页面；扩展订单状态同步。 | 表：`review_rule`、`review_record`；`review.delay`、`review.enabled`。 |
| P1-7 | 自动免邮规则 | 按商品/SKU/订单金额/组合条件执行免邮，保存执行前后状态和失败原因。 | 新增 `shipping_policy`；订单工作流在提交发货前评估；前端在商品配置增加规则。 | 表：`shipping_policy`、`shipping_policy_execution`；`shipping.policy.enabled`。 |
| P1-8 | 订单/商品报表和数据导出 | 在现有日度面板外增加按账号/商品/SKU/渠道的收入、发货成功率、回复转化、库存周转报表，并导出。 | 扩展 `DataPanelController` 的聚合查询；异步导出任务；前端分析页。 | 表：`report_export`；`report.timezone`、`report.max-range-days`。 |

### 长期拓展（P2）

| 项目 | 缺失功能与目标 | 开发内容 | 主要代码改造点 | 新增配置/数据 |
| --- | --- | --- | --- | --- |
| P2-1 | 选品、采集、货源与利润分析 | 建立商品采集、候选池、供应商、成本/利润、选品评分、分销和结算链路。该项对齐 XY-Auto 的采集/分销模块，需先确认平台合规与数据来源授权。 | 新增 `sourcing`、`catalog`、`settlement` 模块；采集器以受控适配器运行；分析任务进入任务中心。 | 表：`source_product`、`supplier`、`selection_rule`、`profit_snapshot`、`distribution_order`、`settlement_record`；数据源/限速/合规开关。 |
| P2-2 | 可插拔扩展框架 | 定义事件、命令、通知和发货渠道 SPI；插件包元数据、版本兼容、启停、权限和沙箱边界。 | 新增 `extension-api`、`extension-runtime`；先支持进程内 Spring SPI，后续再考虑独立进程插件；Webhook 是首个外部扩展实现。 | 表：`extension_registry`、`extension_config`；`extensions.enabled`、允许目录/签名公钥/兼容版本。 |
| P2-3 | 多节点与高可用 | 当账号/任务量超过单机 SQLite 能力时，拆分 API、WebSocket Worker、Task Worker；共享关系库、队列和分布式锁。 | 将 P0 任务/指标/密钥边界抽为独立部署单元；SQLite 迁移至 PostgreSQL/MySQL；Worker 无状态化。 | `DATABASE_URL`、Redis/RabbitMQ、对象存储、Worker 副本数、服务发现/反向代理配置。 |
| P2-4 | 合规控制台 | 集中展示平台规则确认、功能开关审批、风险事件、数据保留和删除请求；所有自动化动作可追溯/可暂停。 | 在风险、审计、任务和权限模型上增加政策规则与审批流。 | 表：`compliance_policy`、`approval_request`、`data_retention_policy`；`compliance.*`。 |

## 5. 建议实施里程碑

1. **第一期（P0-1 至 P0-3）**：先立权限边界和可恢复任务底座。未完成前，不建议扩大自动化范围或开放局域网访问。
2. **第二期（P0-4 至 P0-6）**：补齐可观测、安全与风险暂停，使故障可发现、可解释、可控。
3. **第三期（P1-1 至 P1-4）**：对外协同、通知治理、审计导出和备份运营化。
4. **第四期（P1-5 至 P2）**：以明确的平台规则和真实运营需求决定商品发布、评价、免邮、选品和插件/多节点投入。

## 6. 审计边界与验证建议

- 本次为静态代码和公开资料审计，未使用真实闲鱼账号执行发货、发布、评价或采集，因此不把第三方平台行为的可用性写成已验证事实。
- 当前工作区存在用户未提交的前端与构建产物修改；本审计未回退或改写它们。
- P0-2 的基础实现可保持单机 SQLite，目标是先获得重启恢复、幂等和可视化。只有任务量/并发指标证明单机不足时，再实施 P2-3 的消息队列和多节点。
- 每个补齐项应先建验收测试：权限越权、重启恢复、重复投递、未知结果核对、告警触发、密钥轮换、Webhook 重投，随后才接入真实账号。
