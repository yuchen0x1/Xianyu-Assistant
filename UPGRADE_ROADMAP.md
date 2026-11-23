# 项目升级路线图

版本基线：v3.5.29（本轮升级前为 v3.5.28 / `970489b`）
建立日期：2026-11-16（Asia/Shanghai）

## 使用说明

本路线图把已经存在并经代码/测试确认的能力、当前轮次实施的修复和后续可执行项目放在同一份清单中，避免把“页面存在”误判为“业务已完成”。每一行都包含升级名称、模块、问题、依据、竞品/技术参考、方案、收益、文件、难度、优先级和验收标准。状态只有 `已完成`、`本轮完成`、`部分完成`、`待实施` 四类。

## 升级清单

| 编号 | 升级项 / 模块 | 当前问题与依据 | 对标参考 | 实施方案 | 预期效果 | 文件/模块 | 难度 | 优先级 | 验收标准 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EV-001 | 登录跳转闸复位 / 前端会话 | `redirectingToLogin` 在重新登录后可能仍为 true；`request.ts` | Chatwoot 登录会话反馈模式 | 在 `setAuthToken` 中复位一次性跳转状态 | 会话过期后重新登录可再次正确跳转 | `vue-code/src/utils/request.ts` | 低 | P0 | 过期→登录→再次过期能触发跳转，且只提示一次 | 本轮完成 |
| EV-002 | 权限请求 Token 隔离 / RBAC 前端缓存 | 旧账号慢响应可能覆盖新账号权限缓存；`usePermissions.ts` | Chatwoot 多用户前端状态隔离 | 记录请求对应 Token，Token 变化时不共享 in-flight 并只写入当前缓存 | 避免跨账号权限泄漏和错误菜单 | `usePermissions.ts`、`permissions-races.test.ts` | 低 | P0 | 旧请求后完成仍不污染新 Token 缓存 | 本轮完成 |
| EV-003 | 协议 UUID 并发唯一性 / WebSocket | 旧实现同毫秒生成值可能重复；`XianyuDeviceUtils` | svix/队列系统的稳定请求标识 | `ThreadLocalRandom` + 原子单调时间戳 | 多账号并发注册和发送更少碰撞 | `XianyuDeviceUtils.java`、`XianyuDeviceUtilsTest.java` | 低 | P1 | 2,000 次并发生成值全部唯一且格式有效 | 本轮完成 |
| EV-004 | 401 静默续期与单飞 / 认证 | 过期请求容易同时刷新或重复跳转；`request.ts` | Chatwoot 会话续期、n8n 任务重试 | 共用 `refreshInFlight`，刷新后只重放一次原请求 | 减少并发失效造成的退出和重复请求 | `request.ts`、`request.test.ts` | 中 | P0 | 并发 401 只发一个 refresh，失败清凭据 | 已完成 |
| EV-005 | 幂等 GET 瞬时重试 / 网络层 | 网络抖动会直接向用户暴露失败 | svix retry policy、BullMQ | 仅 GET/HEAD 默认退避重试，POST 需显式开启 | 提高读取成功率，避免副作用重复 | `request.ts` | 中 | P1 | 502/超时按上限重试，4xx/取消不重试 | 已完成 |
| EV-006 | Webhook Outbox / 集成 | 直接发送难以恢复和审计 | svix-webhooks | 先持久化 PENDING，再异步投递、退避、终态记录 | 投递可观察、可重试、可定位 | `webhook/service`、`webhook_delivery` | 高 | P0 | 失败重试、成功终态、损坏载荷终止且历史可查 | 已完成 |
| EV-007 | Webhook 出站 SSRF 防护 / 安全 | 用户配置 URL 可能访问内网或 DNS 重绑定 | svix、OWASP SSRF 防护 | 解析后拒绝回环/私网/链路本地并禁止重定向 | 降低服务端被利用探测内网的风险 | `WebhookService.java` | 高 | P0 | 私网、重定向、异常解析均被拒绝 | 已完成 |
| EV-008 | Webhook 稳定投递编号 / 幂等 | 接收端难以判断重复投递 | svix `id`/幂等模型 | 持久化稳定 ID，发送 `X-Webhook-Id` 和准确尝试序号 | 方便接收端去重和审计 | `WebhookService.java`、DTO | 中 | P1 | 重试 ID 不变、attempt 递增且不截断长键 | 已完成 |
| EV-009 | Webhook 历史筛选与窄屏反馈 / UI | 只有投递动作而缺少可视回放 | Chatwoot Webhook 管理页 | 历史组件、状态筛选、刷新锁、空/错态和响应式布局 | 降低排障成本 | `WebhookDeliveryHistory.vue`、`webhooks/index.vue` | 中 | P1 | 桌面/窄屏均可加载、刷新和筛选 | 已完成 |
| EV-010 | JobEngine 租约与栅栏 / 任务 | 多次执行或旧工作者回写会覆盖新状态 | BullMQ/Temporal 任务租约 | 使用持久化 lease、fencing token 和死信 | 任务重启和并发执行更可控 | `job` 包、V3_16/V3_26 | 高 | P0 | 并发 worker 只有持有最新栅栏者可回写 | 已完成 |
| EV-011 | 发货库存预占回滚 / 履约 | 失败时可能留下占用库存 | 同类自动发货项目的 reservation 模式 | 预占、成功发放、失败/超时回滚，保留审计 | 降低重复发货和库存损失 | `service/delivery`、`kami` | 高 | P0 | 模拟失败/超时后库存与流水一致 | 已完成 |
| EV-012 | 自动回复决策日志 / AI | 用户不知道为何没有回复 | xianyu-super-butler 决策日志 | 记录人工接管、无策略、无商品、生成失败等可追溯原因并去重 | 让 AI 行为可解释 | `AutoReplyDecisionService`、自动回复页 | 中 | P1 | 同会话同原因限时去重，页面可查看 | 已完成 |
| EV-013 | AI 端点 SSRF 与权限闸 / AI 安全 | 可配置端点会把 API Key 发向恶意地址 | OWASP SSRF、OpenAI 兼容客户端边界 | 保存和出站两次校验，敏感端点仅管理员可改 | 防止密钥外带和内网探测 | `AiEndpointValidator`、RAG manager | 高 | P0 | 私网/HTTP/user-info 被拒，合法端点可用 | 已完成 |
| EV-014 | 凭据加密与响应脱敏 / 数据安全 | Cookie、AI Key、Webhook secret 不能明文落库或下行 | Vault/Secret Manager 设计 | AES-GCM 加密、掩码更新保留原值、备份排除凭据 | 降低本地泄露影响 | `CredentialCipher`、实体 TypeHandler、backup handlers | 高 | P0 | 数据库/响应/日志均无明文密钥 | 已完成 |
| EV-015 | 日志敏感字段门禁 / 安全 | 异常日志可能带 token、Cookie、URL 查询值 | OWASP Logging | 静态扫描测试 + 统一错误消毒，禁止输出敏感变量 | 防止日志成为凭据副本 | `security` tests、日志调用点 | 中 | P0 | 敏感回归测试通过且异常消息已消毒 | 已完成 |
| EV-016 | Ed25519 发布物验签 / 供应链 | SHA-256 与发布源同源，不能单独建立信任 | TUF/签名发布实践 | Release 生成 `.sig`，客户端和安装脚本强制验签 | 防止发布源被同时替换 | `SelfUpdateService`、`install.sh`、release workflow | 高 | P0 | 篡改包/缺签名/错误公钥均拒绝 | 已完成 |
| EV-017 | 登录限流与密码策略 / 认证 | 注册和登录需抵抗脚本探测和爆破 | OWASP Authentication | 分档限流、可信代理 IP 处理、密码最小长度 8 | 降低爆破风险且兼容反代 | `AuthService`、`LoginController` | 中 | P0 | 超限返回明确错误，伪造转发头不绕过 | 已完成 |
| EV-018 | RBAC 与账号范围隔离 / 授权 | 多账号运营数据不能跨账号可见 | Chatwoot workspace scope | Controller/Service/Mapper 多层校验和角色权限码 | 降低越权读取/写入风险 | `rbac`、各 Mapper/Controller | 高 | P0 | VIEWER/OPERATOR/ADMIN 与账号范围测试通过 | 已完成 |
| EV-019 | 监控告警状态机 / 可观测性 | 单次告警会重复发送且无法确认/静默 | Uptime Kuma、Prometheus Alertmanager 思路 | 持久化唯一键、抑制窗口、ACK/SILENCE/RESOLVE | 降低噪声、可跟踪恢复 | `monitor` 包、监控页 | 高 | P1 | 重复事件去重，状态迁移和恢复可重放 | 已完成 |
| EV-020 | 备份全量与保留策略 / 数据可靠性 | 只备份部分表会影响恢复 | SQLite backup/自托管工具 | handler 覆盖业务表、排除凭据、定时保留和恢复校验 | 提高灾备可用性 | `backup` 包、`DataBackupScheduler` | 高 | P0 | 空库/老库备份恢复后表和关系一致 | 已完成 |
| EV-021 | 数据库空库初始化顺序 / 部署 | 迁移依赖基础表时新装会中断 | Flyway/Liquibase 初始化顺序 | 先建 schema，再执行增量迁移并保证幂等 | 新装和升级路径一致 | `DatabaseInitListener`、`SchemaMigrator` | 高 | P0 | 空库首次启动和二次启动均成功 | 已完成 |
| EV-022 | MCP JSON-RPC 批量与错误规范 / 自动化 | 工具调用缺少批量和标准错误边界 | Model Context Protocol 生态 | 支持 initialize/schema/batch/invalid item，保持认证/RBAC | AI 工具调用更稳定 | `mcp` controller/command registry | 中 | P1 | 批量请求逐项返回、异常不泄密 | 已完成 |
| EV-023 | WebSocket 心跳/重连 watchdog / 连接 | 长请求或多账号时误判掉线 | XianyuApis 分层、常见 WS supervisor | 独立线程池、心跳超时、指数重连、Cookie 保活巡检 | 降低掉线和恢复延迟 | `websocket`、`WebSocketConnectionSupervisor` | 高 | P0 | 模拟超时后按策略重连并记录健康状态 | 已完成 |
| EV-024 | 风控失败熔断与冷却 / 平台边界 | 连续认证失败继续请求可能放大风险 | 同类 bot 的 auth failure guard | 按账号记录失败窗口，达到阈值自动暂停和冷却 | 降低持续失败和账号风险 | `XianyuAuthFailureGuard`、risk service | 中 | P0 | 连续失败触发暂停，冷却后才恢复 | 已完成 |
| EV-025 | 在线更新失败回滚 / 发布 | 下载/替换失败可能导致服务不可启动 | Electron/Tauri updater 思路 | 临时文件、SHA/签名验证、旧包备份、原子替换、策略化重启 | 提升升级恢复能力 | `SelfUpdateService`、`release.yml` | 高 | P1 | 错包不替换，替换失败可回退并留下状态 | 已完成 |
| EV-026 | CI 质量门禁 / 工程化 | 本地检查容易与 CI 漂移 | GitHub Actions 常规矩阵 | 固定 Action SHA，运行 Maven、Vitest、Lint、类型、构建、audit、Docker | 提前发现回归 | `.github/workflows/ci.yml` | 中 | P1 | PR/push 上所有门禁可复现 | 已完成 |
| EV-027 | 前端 reduced-motion/transparency / UI 可访问性 | 动效和透明材质可能影响可用性 | Fluent/现代 dashboard 设计 | CSS tokens 在系统偏好下降级静态纯色 | 提升低动效和低透明用户体验 | `styles/tokens.css`、各 view CSS | 低 | P2 | 两种系统偏好下无关键内容丢失 | 已完成 |
| EV-028 | 账号连接详情与错误反馈 / UI | 凭据更新、健康状态和失败原因分散 | Chatwoot inbox/connection UX | 统一详情卡、加载/空/错态、手动刷新和脱敏提示 | 降低连接维护门槛 | `views/connection` | 中 | P1 | 更新凭据和恢复连接有明确状态 | 已完成 |
| EV-029 | 订单/商品导出 / 运营效率 | 运营数据离开控制台不便分析 | 电商后台导出功能 | Excel 导出参数校验、权限和超时边界 | 方便离线分析和留档 | `api/export.ts`、export controllers | 中 | P2 | 过滤条件和大结果导出成功/超时提示清晰 | 已完成 |
| EV-030 | 版本一致性 / 发布工程 | POM、前端、version.json、标签可能漂移 | release checklist | 测试校验版本一致，发布前阻断不一致 | 减少错误包和错误升级提示 | `VersionConsistencyTest`、release scripts | 低 | P1 | 版本漂移时 CI 失败 | 已完成 |
| EV-031 | 商品详情浏览器兜底 / 商品同步 | `ItemServiceImpl` 明确标记浏览器兜底未实现 | Playwright/同类采集器分层 | 仅在用户显式启用且凭据可用时打开受控浏览器，提取稳定字段并限时 | API 波动时提高同步成功率 | `ItemServiceImpl`、`PlaywrightManager` | 高 | P1 | API 失败时可控降级，失败不伪造成功且有审计 | 待实施 |
| EV-032 | 历史接口分页 / Webhook、日志、消息 | 固定最近数量会在数据增长后丢失可浏览性 | Chatwoot、Uptime Kuma 列表分页 | 增加 cursor/limit 上限、总数或下一页标识，前端增量加载 | 降低查询负担，支持长期运营 | 对应 Controller/Mapper/页面 | 中 | P2 | 旧调用兼容，分页边界和排序稳定 | 待实施 |
| EV-033 | 多实例持久化租约 / 任务与发货 | 进程内保护不能覆盖多节点 | Temporal/BullMQ worker lease | 明确部署前提后再新增节点身份、数据库租约和 fencing | 支持受控横向扩展 | `job`、`delivery`、新增迁移 | 高 | P1 | kill worker 后新 worker 可接管且旧回写被拒 | 待实施 |
| EV-034 | HttpOnly 会话 Cookie 评估 / 认证 | localStorage Token 受 XSS 影响 | OWASP session management | 设计同源 HttpOnly/SameSite Cookie 迁移，保留旧 Token 兼容窗口 | 降低浏览器脚本窃取面 | `AuthInterceptor`、前端 request、登录接口 | 高 | P1 | CSRF、反代、移动浏览器和升级兼容测试通过 | 待实施 |
| EV-035 | CSP 与安全响应头 / Web 安全 | 当前主要依赖 Vue 模板安全和无第三方脚本 | OWASP CSP | 直接 enforce CSP（脚本仅同源、允许 https 图片和内联样式），加 nosniff/DENY/no-referrer/Permissions-Policy，并移除首屏内联样式 | 降低 XSS/资源注入面 | `SecurityHeadersFilter`、`index.html` | 中 | P1 | 页面、上传、AI、日志下载均正常；`SecurityHeadersFilterTest` 断言不含 unsafe-inline 脚本 | 已完成 |
| EV-036 | 结构化 trace/request ID / 运维 | 跨 HTTP、任务、Webhook、WS 的日志关联仍需统一 | OpenTelemetry/Micrometer | 每请求生成/沿用安全编号，写入响应头与日志 MDC，前端“关于”页展示并可复制 | 缩短排障链路 | `RequestCorrelationFilter`、`logback-spring.xml`、`request.ts` | 中 | P2 | 请求头、日志 `reqId=` 与页面编号一致；异步线程传播仍待补 | 部分完成 |
| EV-037 | 启动/内存/长稳基线 / 性能 | 目前只有功能测试，没有量化运行基线 | Grafana/Prometheus dashboard | 用固定数据集和本地脚本记录启动时间、堆、CPU、查询/WS指标 | 让优化有前后证据 | `scripts`、Actuator、docs | 中 | P2 | 同一环境可重复采集并比较版本 | 待实施 |
| EV-038 | SQLite 维护与大库策略 / 数据层 | 长期运行会产生 WAL、日志和历史增长 | SQLite production checklist | 提供安全 checkpoint、历史保留、VACUUM 前置检查和磁盘告警 | 降低磁盘耗尽和慢查询 | backup/monitor/config | 中 | P2 | 低磁盘告警、维护不破坏并发写入 | 待实施 |
| EV-039 | 备份预检/恢复演练 / 数据可靠性 | 有备份文件不等于可恢复 | restic/SQLite restore practices | 已加只读导入预检（版本、模块、条数、缺失字段）；临时库隔离恢复校验仍待实施 | 发现坏备份而不是出事后才发现 | `DataBackupServiceImpl`、settings 界面 | 高 | P1 | 结构错误在导入前即被拒绝，前端禁止跳过预检 | 部分完成 |
| EV-040 | SBOM 与许可证快照 / 供应链 | CI 有漏洞审计，但缺少可审计依赖清单 | Syft/OWASP Dependency-Track | 在发布前生成 Maven/npm SBOM，保存许可证和依赖版本快照 | 便于漏洞响应与许可审查 | release workflow、docs | 中 | P2 | 发布工件带 SBOM，依赖变化可审阅 | 待实施 |
| EV-041 | 浏览器 E2E 隔离验收 / 测试 | 页面单测不能证明路由、窄屏、权限链路 | Playwright、Chatwoot browser tests | 保持隔离 SQLite/假服务，覆盖登录、关键页面、空/错态、响应式 | 防止跨层回归 | `src/test/.../webhook/WebhookBrowserIT.java`、新增 IT | 高 | P1 | 无真实账号时也可重复跑，截图/日志可审阅 | 部分完成 |
| EV-042 | 无障碍自动检查 / UI | 语义和焦点已有，但缺少自动回归 | axe-core/WCAG | 在隔离 E2E 中加入键盘、焦点、对比度和 aria 断言 | 提升可访问性 | frontend tests、E2E | 中 | P2 | 关键页面无高优先级违规 | 待实施 |
| EV-043 | 配置导入 dry-run / 运维 | 备份/配置错误可能在应用阶段才暴露 | n8n import validation | 新增 `/api/backup/preflight` 与前端预检面板：版本、模块数组、缺失字段、明文凭据提示，通过后才允许导入 | 降低迁移事故 | `DataBackupController`、settings 界面 | 中 | P1 | 预检不写库；结构错误返回明确字段名；预检未通过时导入按钮禁用 | 本轮完成 |
| EV-044 | 插件/命令契约版本化 / 扩展 | MCP/命令扩展依赖内部类结构 | MCP tool schema、n8n node contract | 为命令输入输出加 schema version、能力发现和弃用标记 | 扩展升级更稳定 | `command`、`mcp` | 高 | P2 | 旧客户端仍可调用，未知版本给明确错误 | 待实施 |
| EV-045 | 多语言与时区显式化 / UI 与调度 | 目前界面中文、业务时间依赖本地配置 | Vue i18n、Quartz 时区实践 | 抽取用户可见文案，设置显示时区与 Cron 解释，保持数据库 UTC/本地兼容 | 降低跨地区使用误解 | frontend views、schedule services | 中 | P3 | 切换语言/时区后列表、Cron、通知一致 | 待实施 |

## 优先执行顺序

1. **P0**：EV-001、EV-002、EV-004、EV-006、EV-007、EV-010、EV-011、EV-013、EV-014、EV-015、EV-016、EV-017、EV-018、EV-020、EV-021、EV-023、EV-024。
2. **P1**：EV-003、EV-005、EV-008、EV-009、EV-012、EV-019、EV-022、EV-025、EV-026、EV-028、EV-030、EV-031、EV-033、EV-034、EV-039（剩余临时库恢复校验）、EV-041、EV-043（已完成）。
3. **P2/P3**：EV-027、EV-029、EV-032、EV-036（HTTP 已完成，异步传播待补）、EV-037、EV-038、EV-040、EV-042、EV-044、EV-045。

## 实施边界

商品详情浏览器兜底、HttpOnly 会话、多实例租约、真实平台 E2E 和更新恢复演练都需要外部平台/部署条件或明确迁移设计。本地环境可以先完成接口契约、隔离测试和 dry-run，但不能把占位代码或模拟响应标为完成。
