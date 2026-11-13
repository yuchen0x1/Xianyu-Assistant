# v3.5 系列原始技术记录

> 当前产品范围（2026-11-13）：仅维护网页版，Android/iOS 工程与独立桌面启动包装已移除。下文的原生移动端功能和检查结果仅为旧版本历史记录，不代表当前提供独立客户端。电脑和手机均通过浏览器访问。

本页保留日志整理前的实现与历史检查记录，供追溯。测试数量及平台结论是当时记载，未经本轮重测；准确的升级影响、补记与限制以[发布记录](release-notes.md)为准。原日志中的“全部通过”可能包含条件跳过，不能视为逐项执行通过。原档案把 V3_26 称为 Flyway 迁移，实际由项目自有 SchemaMigrator 执行；v3.5.16 还包含 V3_25。v3.5.22 的 Windows 替换与安装器公钥修复已补记在主发布记录中。

## v3.5.24

> Release: 2026-11-13

- Webhook 新增持久化投递历史面板：最近 100 条、状态筛选、加载/失败恢复、累计尝试次数和下次重试时间；适配窄屏。
- 修复空事件数组被视为“不监听任何事件”，使“不选择则全部监听”的界面与后端一致；格式损坏的订阅不再按子串误匹配。
- 修复成功后尝试次数清零、空值未落库导致旧错误/重试时间残留；损坏载荷明确停止重试，队列拒绝不消耗发送次数。
- 首次排队与重投共用进程内占用保护；执行前复核成功状态与尝试上限；异步结果仅更新运行字段，避免覆盖用户新配置。
- 增加 `X-Webhook-Id` 与准确的 `X-Webhook-Attempt`；过长幂等键改为 SHA-256 摘要，避免截断丢失业务标识和时间片。
- HTTP 异常只记录异常类型及安全提示，避免 URL 查询参数中的凭据进入日志和历史。
- 无依赖升级或数据库结构迁移，旧配置保持兼容；版本统一为 3.5.24。

## English

- View and filter persisted Webhook delivery history, including attempt counts, HTTP status, failure reasons and retry times.
- Fix all-event subscriptions, stale failure state after success, and asynchronous delivery results overwriting newly edited settings.
- Improve duplicate-delivery protection and stable delivery identifiers, stop corrupt payload retries, and keep URL credentials out of request-error messages.
- Add loading feedback and mobile-friendly history; no dependency upgrades or database migrations.
- See `docs/webhook-delivery.md` for compatibility and remaining end-to-end validation boundaries.

## v3.5.23

> Release: 2026-11-12

- **功能：有序多段自动发货模板**：支持模板与商品 / SKU 绑定，使用订单、买家和卡密变量渲染消息，并通过 WebSocket 按顺序发送；记录 `delivery_phase` 检查点，断线或重试时可从已完成段落继续。
- **可靠性：卡密资源一致性**：模板发货支持卡密预占、成功提交、明确失败回滚和未知结果保留，避免重试时重复消耗库存。
- **备份：模板可迁移**：备份导出 / 导入覆盖发货模板及模板消息，并保持旧备份和旧的单段自动发货配置兼容。
- **功能：自动回复匹配增强**：关键词回复支持多关键词，以及模糊、精确和正则匹配模式。
- **运维：同类资源检测**：支持 `force=true` 强制刷新检测结果，并把当前发布仓库纳入可见基线。
- **迁移：V3_27**：新增模板相关表和配置字段，采用增量迁移，不清空已有业务数据。
- **验证**：后端 703 项测试通过（0 失败 / 0 错误 / 3 跳过）；前端 40 项测试、TypeScript 类型检查、Lint 和生产构建通过。

## v3.5.22

> Release: 2026-11-12

- **功能：在线自动更新增强**：支持稳定版、Beta 和 Dev 更新通道，预发布版本保留完整 SemVer 后缀并正确定位 Release 资产。
- **可靠性：下载流程加固**：下载失败自动重试、取消下载、磁盘空间预检、真实速度与预计剩余时间，以及临时文件清理。
- **安全：发布物校验**：继续强制 SHA-256 校验和官方仓库 Ed25519 签名校验，验证通过后才执行原子替换。
- **运维：发布流水线**：GitHub Actions 发布前执行后端测试、前端测试和 TypeScript 类型检查；新增自动更新配置与故障处理文档。
- **验证**：后端 690 项测试通过、3 项跳过；前端 40 项测试通过，类型检查和生产构建通过。

## v3.5.21

> Release: 2026-11-12

- **安全：修复数据面板账号范围过滤静默失效（高）**：`XianyuChatMessageMapper`、`XianyuGoodsAutoReplyRecordMapper`、`XianyuOperationLogMapper` 中 7 个查询存在同名重载，其中带 `DataPanelAccountScope` 的版本声明在后。MyBatis 的语句 ID 只由「接口全名 + 方法名」构成，后注册的带范围版本被静默丢弃（启动日志 7 条 `is ignored, because it exists`），导致非 ADMIN 用户在数据面板看到全部账号的统计、趋势、咨询排行、关键词排行、操作统计与失败原因排行，且不报错。现统一改名为 `...Scoped` 后缀消除重载，并在调用点同步；启动日志冲突告警降为 0。
- **测试：Mapper 重载防腐 + 装配级范围回归**：新增 `MapperOverloadGuardTest`（反射扫描全部 Mapper 接口，禁止同名方法）与 `DataPanelMapperSqlScopeTest`（真实装配 MyBatis + SQLite 双账号数据，对 7 个查询分别断言 ADMIN 全量 / 空范围拒绝全部 / 单账号只见自身）。两个测试均已用「注入旧重载」反向验证会失败，不是空测试。
- **修复：批量擦亮缺少上限与限流**：`BatchPolishItemsReqDTO` 补 `@Size(max = 50)`，接口补 `@RateLimit(bucket = "batch_polish", limit = 3)`；「商品列表留空 = 擦亮全部在售」的服务层分支同样受 50 件上限约束（该分支原可绕过 DTO 校验）。
- **修复：批量操作必然前端超时**：批量下架/批量擦亮/批量改价在请求线程内逐件调用平台接口且件间 `Thread.sleep(1000)`，而前端全局超时为 30 秒——约 30 件以上必定 `ECONNABORTED`，但服务端继续执行，用户重试会造成重复操作。现三个批量接口显式设置 120 秒超时；「一键擦亮在售」在前端按 50 件一批串行提交，大账号不再直接收到 400。
- **修复：未知接口路径返回 200 + `code=500`**：`WebMvcConfig` 的 SPA 回退把未知 `/api/` 路径交给全局兜底，包装成 HTTP 200 +「系统繁忙」并打完整堆栈，既误导调用方也淹没真实 500。新增 `NoResourceFoundException` 处理，明确返回 HTTP 404 + `code=404`。
- **修复：actuator 端点被 SPA 吞成 HTML**：`/actuator/prometheus` 未从 SPA 回退中排除，实测返回 `index.html`（`Content-Type: text/html`），调用方以为指标抓取成功。`WebMvcConfig` 现将 `actuator/` 路径同样排除；`management.endpoints.web.exposure.include` 改为可通过 `XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS` 配置，默认仍只开放 health/info，需要指标采集时显式设置。
- **修复：Windows 上浏览器兜底必然失效**：`PlaywrightManager.findSystemChromium()` 此前只探测 Linux 的 `/usr/bin/*`，缓存回退路径硬编码 `chrome-linux/chrome`，Windows/macOS 上即使装了浏览器也永远找不到，Cookie 兜底刷新静默降级为「只能重新扫码」。现按平台枚举系统浏览器（Chrome/Edge）、`PLAYWRIGHT_BROWSERS_PATH` 与用户级 Playwright 缓存（`%LOCALAPPDATA%\ms-playwright` 等），并按平台匹配 `chrome-win64/chrome.exe` 等相对路径；启动时做一次只读探测，找不到则明确告警并给出安装指引，新增 `isBrowserAvailable()` / `getDetectedBrowserPath()` 供健康检查使用。
- **修复：改密口令策略弱于注册**：注册要求 8 位而改密仅要求 6 位，弱口令可从改密入口绕过。新增 `PasswordPolicy` 作为单一来源，注册与改密共用。
- **修复：构建元数据白打进包**：`version.json` 由 Maven 过滤生成却无任何读取方。新增 `BuildInfoContributor` 把它输出到 `/actuator/info`（`build.version` / `build.time`），运维排查「跑的是哪次构建」不再只能看版本号。
- **清理**：移除未使用的 Pinia 依赖与 `createPinia()` 注册（全仓 0 个 `defineStore`）、死代码 `BusinessMetricsService`（0 引用）、10 个无引用的 Vue 组件与其过时测试；`RefreshCookieDialog.vue` 因被前端安全回归测试作为把关目标引用而保留。
- **CI**：前端任务新增 `npm run lint:check` 关卡（此前 lint 只在本地跑，规则可被长期违反）。
- **测试**：新增 `VersionConsistencyTest`（pom 版本与 5 处 Java `@Value` 兜底、`application.yaml`、3 处前端 `__APP_VERSION__` 兜底逐一比对，防止 3.5.18 那类版本漂移复发）、`PasswordPolicyTest`、`GlobalExceptionHandlerTest` 增补 404 用例、`PlaywrightManagerTest` 增补跨平台路径断言。
- **验证**：后端 688 项测试全绿（0 失败 / 0 错误 / 3 跳过）；前端 40 项 Vitest、type-check、ESLint、生产构建全部通过；Flutter 单测、Dart 静态分析和 Android debug APK 构建通过；临时端口实机启动确认启动日志 0 条 mapper 冲突、浏览器自动定位到本机 Chrome、未知 API 返回 404、`/actuator/info` 输出构建信息、prometheus 默认 404 且显式开启后返回 `text/plain` 指标、SPA 路由回退不受影响；现有 12400 实例未被触碰。

## v3.5.20

> Release: 2026-11-08

- **功能：待办中心**：新增聚合接口 `GET /api/todo/overview`，按当前用户 RBAC 账号范围汇总四类高频待办（待发货订单、待回复会话、发货待复核、自动评价待确认），单个数据源失败不影响其他类别；Web 控制台新增「待办中心」页面（工作台分组首位入口），提供四类统计卡片、分类 Tab、自动刷新与时间格式化，点击条目直达对应处理页。
- **功能：商品批量下架**：新增 `POST /api/publish/batchOffShelf`，单批上限 50 件、逐件间隔 1 秒规避平台频控、独立限流桶（3 次/分钟）、逐件成功/失败明细，单件失败不中断批次；商品列表新增多选（仅在售可选，桌面表格与移动卡片均支持勾选与表头全选含半选态），切换账号、状态筛选或翻页自动清空选择以避免跨账号/跨页误操作，下架为危险操作需二次确认。
- **功能：商品批量改价**：新增 `POST /api/items/batchUpdatePrice`，将选中商品统一更新为新售价（`goods:write` 权限 + RBAC 账号校验 + 独立限流桶，逐件返回成功/失败明细）；底层新增专用 `updateItemPrice` 只修改售价与更新时间，不覆盖标题、详情、图片，金额规则沿用既有校验（大于 0、最多两位小数、上限 1 亿）；界面与接口消息明确标注仅更新本地商品资料、闲鱼远程商品需在官方页面确认，不伪装远程改价成功。
- **移动端：Flutter 原生工作台**：首页从纯 WebView 壳升级为原生工作台——服务在线状态与版本展示、服务地址配置与持久化、下拉刷新健康探测（`/api/system/health`）、待办中心入口、待发货/待回复/待复核/待评价四个快捷入口、「打开完整控制台」按钮；完整控制台支持按入口深链加载指定业务路由，WebView 导航白名单保持严格限制在配置服务的 scheme/host/port，原生层不存储登录 Token。
- **验证**：新增 `TodoServiceImplTest`、`PublishServiceImplBatchOffShelfTest`、`ItemServiceImplBatchUpdatePriceTest` 三组回归测试；后端全量 662 项测试通过（0 失败 / 0 错误 / 3 跳过）；前端类型检查、34 项 Vitest 与 Vite 生产构建通过；Flutter `analyze`/`test` 全绿，Android debug APK 构建成功。

## v3.5.19

> Release: 2026-11-07

- **修复：Windows 独立 JAR 部署下在线自更新被误判为「IDE 开发模式」**：旧逻辑仅依赖 `ProcessHandle...arguments()` 从进程启动参数定位当前 JAR，而 Windows JVM 经常取不到自身启动参数（返回 empty），导致以 `java -jar` 方式运行的实例在「版本更新」弹窗中出现「未以独立 JAR 方式运行，在线更新不可用」的误报。现定位链扩展为：启动参数 → 环境变量 `XIANYU_ASSISTANT_JAR_PATH` → Spring Boot `ApplicationHome`（从类加载来源解析实际运行 JAR；IDE 模式解析出 classes 目录自动过滤不误判）。
- **修复：自拉起命令的 java 可执行文件回退加固**：`restart-mode=self` 重启时若 `ProcessHandle...command()` 也不可得，回退目标从依赖 PATH 的裸 `java` 改为 `$JAVA_HOME/bin/java(.exe)`（即当前正在运行的这份 JVM），避免解析到错误版本的运行时。
- **验证**：`SelfUpdateServiceTest` 新增 5 项（ApplicationHome 真实 JAR 兜底、classes 目录不误判、参数优先级、java 启动器回退），后端全量 655 项测试通过（0 失败 / 0 错误 / 3 跳过）；Windows 实机验证独立 JAR 启动日志出现「在线自更新已就绪: jarPath=...target\XianYuSellerConsole-3.5.19.jar」。

## v3.5.18

> Release: 2026-11-06

- **功能：商品擦亮（Polishing）**：新增单品擦亮 `POST /api/items/polish` 与批量擦亮 `POST /api/items/batchPolish`，基于闲鱼 H5 端 `mtop.taobao.idle.item.polish`（协议版本 2.0）实现；批量模式可显式指定商品列表，留空时自动取该账号下全部在售商品，逐件间隔 1 秒以规避平台频控，并返回逐件成功/失败明细。
- **功能：定时自动擦亮**：计划任务新增 `POLISH_GOODS` 任务类型，支持用 Cron 表达式定时批量擦亮账号商品（可指定商品 ID，留空则全量在售），配合平台每日擦亮次数上限实现长期免费曝光维护。
- **前端：商品管理接入擦亮 UI**：商品列表操作列新增「擦亮」按钮（仅在售商品可见），页头新增「一键擦亮在售」批量按钮（含 Loading 态与结果提示）；计划任务新建/编辑弹窗新增「自动擦亮」类型与商品 ID 输入项。
- **UI：深空赛博视觉体系全面升级**：重塑全局设计令牌（`tokens.css`）——新增霓虹电光青/赛博蓝/电离紫三色光效体系，深色模式升级为深空底色 + 青色霓虹描边 + 24px 高精磨砂玻璃；矩阵微光点阵背景、流光扫过按钮、卡片悬浮升维辉光、雷达呼吸状态灯、霓虹能量条侧边导航、渐变裁切标题、发光图表柱与发光滚动条。
- **安全/防封：AI 回复延时拟人化抖动**：`ReplyConfigProvider.getDelaySeconds()` 引入高斯正态分布随机因子（σ=0.15，约 ±25% 波动，最低保底 3 秒），打散固定时间回复的机器行为指纹，降低触发平台自动化风控的概率。
- **修复：版本号回退值漂移**：`application.yaml`、`SystemController`、`McpServerController`、`SelfUpdateService`、`UpdateCheckService` 及前端 `AppLayout.vue`、`UpdateDialog.vue`、`AboutPanel.vue` 中残留的旧版本回退值统一对齐当前版本，避免未注入 `app.version` 时自更新检查误判版本。
- **代码质量**：`WebSocketController.CaptchaMouseReqDTO` 补充 `@EqualsAndHashCode(callSuper = false)` 消除 Lombok 编译告警；前端 `useGoodsManager.ts` 的 `catch (err: any)` 改为 `unknown` 严格收窄，ESLint 零告警。
- **验证**：后端 651 项单元测试全绿（0 失败 / 0 错误 / 3 跳过）；前端 ESLint、TypeScript 类型检查、34 项 Vitest 用例与 Vite 生产构建全部通过；Flutter `analyze` 与 `flutter test` 全绿。

## v3.5.17

> Release: 2026-11-04

- **功能：服务器无人值守自动更新**：新增 `SelfUpdateAutoScheduler` 定时调度器，默认每 6 小时（启动 5 分钟后首检）检查官方 GitHub Release，发现新版本自动执行 下载 → SHA-256 + Ed25519 验签 → 原子替换（失败回滚）→ 按 `restart-mode` 重启；`exit` 模式（默认）配合 systemd `Restart=always` 即可实现"自动更新 + 更新后自动重启"闭环。可通过 `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false` 关闭，检查间隔/首检延迟分别由 `XIANYU_ASSISTANT_AUTO_UPDATE_CHECK_INTERVAL_MS` / `XIANYU_ASSISTANT_AUTO_UPDATE_INITIAL_DELAY_MS` 控制。
- **重构：更新检查逻辑抽取为 `UpdateCheckService`**：原 `SystemController` 内的在线更新检查（GitHub/Gitee 源解析、主机白名单、5 分钟缓存、版本比较）整体迁移为独立服务，`/api/system/checkUpdate` 端点行为不变，供控制器与自动更新调度器共用。
- **修复：自动发货 UNKNOWN 发送结果转「待人工复核」**：WebSocket 发送结果为 UNKNOWN（帧可能已写出、成败未确认）时不再按失败处理，统一持久化为 PENDING 待人工复核，并在复核状态持久化失败时主动告警；新增 `DeliveryOrderState` 集中管理发货订单状态与待复核判定，替换裸 -1/0/1 字面量；`MessageSendResult` 语义澄清为「结果未确认，调用方不得假定失败」。
- **安全：CI 供应链加固**：`ci.yml` 的 `subosito/flutter-action` 固定为完整 commit SHA；Dockerfile 构建镜像时实际运行后端测试（移除 `-DskipTests`）；前端 `minimatch` 覆盖升级至 `^11.0.1` 修复 npm audit 漏洞；新增 `SECURITY.md`（漏洞报告政策）与 `security-audit-report.md`（安全审计报告）。
- **开发者**：`start-dev.ps1` 正确引用 JAR 路径，兼容含空格的项目目录。
- **文档**：`docs/production-deployment.md` 补充 systemd 单元自动重启要求（`Restart=always`）与应用内自动更新说明；`README` 配置参考补齐 `XIANYU_ASSISTANT_SELF_UPDATE_*` 与 `XIANYU_ASSISTANT_AUTO_UPDATE_*` 环境变量。
- **验证**：新增 `SelfUpdateAutoSchedulerTest`（13 项：开关/互斥/失败/超时/manual 模式等分支）与 `UpdateCheckServiceTest`（自原 controller 测试迁移）；扩展 `AutoDeliveryServiceImplTest` 与 `XianyuWebSocketClientSendResultTest` 覆盖 UNKNOWN 结果分支；`WebMvcUpdateAuthenticationTest` 适配服务注入，鉴权行为断言不变。

## v3.5.16

> Release: 2026-11-03

- **功能：任务调度增加租约围栏（防重复/脑裂执行）**：新增 V3_26 Flyway 迁移与 `JobDefinition` 租约字段，`JobEngine` 在运行任务前获取并定期续期租约，避免多实例或异常恢复下同一任务被重复执行或并发脑裂。
- **发布：轮换 Ed25519 发布签名密钥**：原签名私钥已丢失（不在仓库亦不在 CI Secret），导致此前签名发布无法完成。本次生成全新 Ed25519 密钥对，新验证公钥已内置进客户端（`SelfUpdateService`）。**已在旧版本上的用户需重新下载 v3.5.16 安装包**以完成密钥信任轮换；自更新通道此后恢复强制验签。
- **构建：三处版本坐标对齐到 3.5.16**（`pom.xml` 项目版本、前端 `vue-code/package.json`、移动端 `mobile/pubspec.yaml`），消除发布脚本的版本一致性校验失败。
- **验证**：后端单元测试（含租约围栏与 V3_26 schema 迁移）、前端类型检查与构建全绿；`release.yml` 自动构建、签名并发布 Release（含 JAR、SHA-256 与 `.jar.sig`）。
