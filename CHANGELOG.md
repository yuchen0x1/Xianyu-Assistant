# 版本更新日志

> 当前产品范围（2026-11-13）：仅维护网页版，Android/iOS 工程与独立桌面启动包装已移除。下文的原生移动端功能和检查结果仅为旧版本历史记录，不代表当前提供独立客户端。电脑和手机均通过浏览器访问。

## 未发布（2026-11-13）

- 产品统一为网页版：电脑与手机通过浏览器访问同一控制台，停止提供独立 Android/iOS 与桌面客户端。
- 清理原生客户端源码和构建入口，服务端 JAR、Docker 部署及响应式网页继续保留。

按版本倒序排列。面向使用者的完整历史见 [发布记录](docs/releases/release-notes.md)，[中文说明](RELEASE_NOTES_CN.md)与[English notes](RELEASE_NOTES_EN.md)覆盖当前 GitHub 的 9 个正式版本。

日期沿用项目历史记录（北京时间），不把标签重建或迁仓日期当作原始发布日期。未记载的日期保持未知；旧版本功能和发布源描述仅反映当时状态。官方发布源在 2026-11-03 随 v3.5.16 迁至 GitHub，此前 Gitee 说明属于历史记录。旧测试记录不表示本轮重新执行或验证真实交易。v3.5 系列的原始实现记录保存在[技术档案](docs/releases/technical-history-v3.5.md)。

# Version 3.5.24

> Release: 2026-11-13

Webhook 投递历史与可靠性。

### 主要变化

- 投递历史：查看最近 100 条记录，按状态筛选、刷新，并查看累计尝试次数、HTTP 状态、失败原因和下次重试时间。
- 订阅与状态修复：未选择事件时监听全部事件；成功后清除旧错误和重试时间，保留真实尝试次数。
- 重试更可靠：首次排队与重投共享进程内占用保护，发送前检查成功状态和次数上限；损坏载荷停止重试，队列拒绝不消耗发送次数。
- 异步投递结果不再覆盖刚编辑的配置；稳定投递编号和准确的尝试序号方便接收端去重，过长幂等键不再直接截断。
- 保存、测试和历史加载提供等待反馈，手机屏幕可正常查看；请求异常提示不再带出 URL 中的凭据。

### 升级说明

- 无新增数据库迁移或依赖升级；原配置和签名格式兼容。接收端仍需幂等处理，进程内保护不等于跨实例“恰好一次”投递。

### 验证记录与边界

- 本版本发布时本地后端 710 项测试中 707 通过、3 项条件跳过，前端 43 项通过；桌面/手机尺寸浏览器验收、main/tag CI 和 Release 流水线通过。已下载正式 JAR 并验证 SHA-256、Ed25519 签名和包内版本；真实旧客户端安装升级及真实交易回调未实测。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.24/XianYuSellerConsole-3.5.24.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.24/XianYuSellerConsole-3.5.24.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.24/XianYuSellerConsole-3.5.24.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.24) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.24) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.23...v3.5.24)

## English

> Release: 2026-11-13

Webhook history and delivery reliability.

### Changes

- Delivery history shows the latest 100 records with status filters, refresh, cumulative attempts, HTTP status, failure reasons and retry times.
- An empty event selection subscribes to all events. Successful deliveries clear stale errors and retry times while retaining actual attempt counts.
- Initial dispatch and retries share an in-process guard and recheck delivery status and attempt limits. Invalid payloads stop retrying; queue rejection does not consume a send attempt.
- Delivery results no longer overwrite recently edited settings. Stable delivery IDs, accurate attempt headers and hashed long idempotency keys support receiver deduplication.
- Saving, testing and history loading provide clear feedback on desktop and mobile. Request errors omit credentials embedded in URLs.

### Upgrade notes

- No new database migration or dependency upgrade. Existing configuration and signature formats remain compatible. Receivers still need idempotency; the in-process guard does not provide cross-instance exactly-once delivery.

### Verification and limits

- At release time, 707 of 710 backend tests passed with 3 conditional skips, and 43 frontend tests passed. Desktop/mobile browser checks, main/tag CI and the release workflow passed. The downloaded release JAR passed SHA-256, Ed25519 and packaged-version checks. A real old-client installation upgrade and live transaction callbacks were not tested.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.24) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.24) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.23...v3.5.24)

# Version 3.5.23

> Release: 2026-11-12

多段发货模板与回复匹配。

### 主要变化

- 发货模板：模板可绑定商品或 SKU，使用订单、买家、商品和卡密变量；消息按顺序发送，并记录已完成段落以便恢复。
- 卡密一致性：发送前预占，明确成功后提交，明确失败时回滚；结果未知时保留并进入人工复核，避免重试重复消耗库存。
- 备份支持发货模板和模板消息导入导出，并继续兼容旧备份及单段自动发货配置。
- 自动回复支持多关键词，以及模糊、精确和正则匹配；同类资源检测支持强制刷新并显示当前项目发布基线。

### 升级说明

- 新增 V3_27 增量迁移，创建模板相关表并补充配置字段；升级前备份 dbdata，保留数据库及密钥文件。旧单段配置可继续使用。
- 结果未知的发货仍需人工核对；断点恢复和库存保护不代表外部平台必然收到消息。

### 验证记录与边界

- 历史发布记录包含后端测试、前端 40 项测试、类型检查、Lint 和生产构建通过；本次日志整理未重跑旧版本测试。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.23/XianYuSellerConsole-3.5.23.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.23/XianYuSellerConsole-3.5.23.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.23/XianYuSellerConsole-3.5.23.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.23) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.23) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.22...v3.5.23)

## English

> Release: 2026-11-12

Multi-part delivery templates and reply matching.

### Changes

- Bind delivery templates to goods or SKUs, render order, buyer, item and card-key variables, and send messages in order with persisted segment checkpoints.
- Reserve keys before sending, commit on confirmed success, roll back on explicit failure, and retain unknown outcomes for manual review without consuming inventory again on retry.
- Backup import/export includes templates and template messages while retaining compatibility with older backups and single-message delivery settings.
- Auto-replies support multiple keywords with fuzzy, exact and regex matching. Similar-resource checks support forced refresh and include this project’s release baseline.

### Upgrade notes

- Additive migration V3_27 creates template tables and configuration fields. Back up dbdata, including the database and key files, before upgrading. Existing single-message configurations remain usable.
- Unknown delivery outcomes still require manual review. Checkpoint recovery and inventory protection do not prove receipt by the external platform.

### Verification and limits

- Historical release records report backend checks, 40 frontend tests, type checking, lint and production build passing. Historical tests were not rerun for this documentation update.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.23) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.23) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.22...v3.5.23)

# Version 3.5.22

> Release: 2026-11-12

在线更新通道与 Windows 替换修复。

### 主要变化

- 更新通道：支持稳定版、Beta 和 Dev，保留预发布版本完整 SemVer 后缀并选择匹配的 Release 附件。
- 下载过程增加失败重试、取消、磁盘空间预检、真实速度和剩余时间展示，并清理临时文件。
- Windows 更新先暂存新包，退出后再替换被占用的 JAR；修正 Linux 安装脚本的内置 Ed25519 公钥，使其与应用更新器一致。
- 官方包继续强制 SHA-256 和 Ed25519 校验；发布前增加后端、前端和类型检查，并补充自动更新配置与排障说明。

### 升级说明

- 无新增数据库迁移。Windows 运行中 JAR 的替换延后到退出之后；exit 模式需要外部进程管理器拉起，self 模式由应用安排重启。
- 旧安装脚本若因旧公钥验签失败，请获取官方新版脚本；不要关闭验签。Beta/Dev 通道只有在对应预发布版本实际存在时才有可选更新。

### 验证记录与边界

- 历史发布记录包含后端与前端 40 项测试、类型检查和生产构建通过。流水线成功或包验签通过不能代替旧客户端安装升级验收。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.22/XianYuSellerConsole-3.5.22.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.22/XianYuSellerConsole-3.5.22.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.22/XianYuSellerConsole-3.5.22.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.22) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.22) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.21...v3.5.22)

## English

> Release: 2026-11-12

Update channels and Windows replacement fixes.

### Changes

- Stable, Beta and Dev channels preserve complete prerelease SemVer suffixes and select matching release assets.
- Downloads support retries, cancellation, disk-space checks, actual speed and ETA reporting, and temporary-file cleanup.
- Windows updates stage the new package before replacing the locked JAR after exit. The installer’s embedded Ed25519 public key now matches the application updater.
- Official packages continue to require SHA-256 and Ed25519 verification. Release preflight adds backend, frontend and type checks, with update configuration and troubleshooting documentation.

### Upgrade notes

- No new database migration. Windows replacement occurs after process exit; exit mode needs an external supervisor, while self mode schedules a restart.
- If an old installer fails signature verification due to its embedded key, obtain the current official installer rather than disabling verification. Beta/Dev updates require an actual matching prerelease.

### Verification and limits

- Historical release records report backend checks, 40 frontend tests, type checking and production build passing. Workflow success and package verification do not replace old-client installation testing.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.22) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.22) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.21...v3.5.22)

# Version 3.5.21

> Release: 2026-11-12

账号隔离与批量操作修复。

### 主要变化

- 修复数据面板账号范围过滤失效：非管理员的统计、趋势、排行和操作数据按授权账号范围展示。
- 批量擦亮增加每批 50 件上限及限流；一键擦亮串行分批提交。批量下架、擦亮和本地改价使用 120 秒请求超时，减少误判超时后的重复提交。
- Windows/macOS 可探测系统 Chrome、Edge 和 Playwright 缓存，找不到浏览器时给出明确提示。
- 注册与改密共用口令策略；未知 API 返回 HTTP 404，监控端点不再返回前端页面，构建信息可用于确认实际运行版本。

### 升级说明

- 无新增数据库迁移；批量改价仍只更新助手本地资料。Prometheus 默认不开放，需要时通过 XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS 显式配置。

### 验证记录与边界

- 历史记录包含后端、前端 40 项测试、Lint/类型检查/构建、Flutter 分析/测试和 Android 调试包构建，以及隔离实例的接口与监控检查；当时本机 Docker 验证受运行环境限制。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.21) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.21) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.20...v3.5.21)

## English

> Release: 2026-11-12

Account isolation and batch-operation fixes.

### Changes

- Fix data-panel account filtering so non-admin statistics, trends, rankings and operations follow authorized account scope.
- Batch polishing is limited to 50 items with rate limiting and serial batches. Off-shelf, polishing and local price updates use 120-second request timeouts to reduce duplicate submissions after premature timeouts.
- Windows/macOS browser discovery checks installed Chrome, Edge and Playwright caches and reports missing-browser guidance.
- Registration and password changes share one password policy. Unknown APIs return HTTP 404, monitoring endpoints no longer fall back to HTML, and build information identifies the running version.

### Upgrade notes

- No new database migration. Batch price updates still affect local assistant records only. Prometheus remains disabled unless explicitly exposed through XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS.

### Verification and limits

- Historical records cover backend checks, 40 frontend tests, lint/type/build checks, Flutter analysis/tests, an Android debug build and isolated API/monitoring checks. Local Docker verification was limited by the host environment.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.21) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.21) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.20...v3.5.21)

# Version 3.5.20

> Release: 2026-11-08

待办中心与移动端原生工作台。

### 主要变化

- 待办中心汇总待发货、待回复、发货待复核和评价待确认，按授权账号过滤，可分类查看并跳转处理；单个来源失败不影响其他类别。
- 商品支持批量下架，每批最多 50 件，逐件反馈结果；切换账号、筛选或翻页清空选择，下架前再次确认。
- 批量改价只修改助手本地售价及更新时间，不覆盖商品标题、详情和图片；界面明确提醒到官方页面确认远程价格。
- Flutter 首页新增服务地址保存、健康状态、版本展示、下拉刷新和四类待办快捷入口；完整控制台仍通过受服务地址白名单限制的 WebView 打开。

### 升级说明

- 无新增数据库迁移；移动端需要连接已部署的服务，原生层不保存登录 Token。Release 提供服务端 JAR，移动端需从源码构建。

### 验证记录与边界

- 历史发布记录包含后端、前端 34 项测试和构建、Flutter 分析与测试、Android 调试包构建通过。批量改价的本地成功不表示平台远程售价已修改。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.20) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.20) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.19...v3.5.20)

## English

> Release: 2026-11-08

Todo center and native mobile dashboard.

### Changes

- The todo center groups pending shipments, replies, delivery reviews and rating confirmations within authorized accounts, with filtered lists and direct navigation. One failed source does not hide other categories.
- Bulk off-shelf operations accept up to 50 items with per-item results. Account, filter or page changes clear selection, and destructive actions require confirmation.
- Bulk price edits change only local price and update time, preserving titles, descriptions and images. The UI directs users to verify remote prices on the official platform.
- The Flutter home screen adds a saved server address, health/version display, pull-to-refresh and four todo shortcuts. The full console opens in a WebView restricted to the configured server origin.

### Upgrade notes

- No new database migration. Mobile requires a deployed server and does not store login tokens in the native layer. The release supplies the server JAR; mobile builds require source.

### Verification and limits

- Historical release records report backend checks, 34 frontend tests/build, Flutter analysis/tests and an Android debug build passing. Local price-edit success does not mean the remote platform price changed.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.20) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.20) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.19...v3.5.20)

# Version 3.5.19

> Release: 2026-11-07

Windows 独立 JAR 更新识别修复。

### 主要变化

- 修复 Windows 通过 java -jar 启动却被误判为 IDE 开发模式、在线更新不可用的问题。
- JAR 定位依次使用进程参数、XIANYU_ASSISTANT_JAR_PATH 和 Spring Boot 应用来源，classes 目录仍不会被当作可更新安装包。
- self 重启模式在无法取得启动命令时使用当前 Java 运行时的启动器，减少 PATH 指向不同 Java 版本导致的问题。

### 升级说明

- 无新增数据库迁移。旧 Windows 版本若无法使用在线更新，需要手动下载并替换 JAR 后启动；本版解决识别问题，运行中 JAR 替换加固在 v3.5.22 补充。

### 验证记录与边界

- 历史记录确认更新服务测试和 Windows 独立 JAR 启动时的就绪识别；不能据此声称完整下载、替换、重启链路已在所有平台实测。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.19) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.19) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.18...v3.5.19)

## English

> Release: 2026-11-07

Windows standalone-JAR update detection.

### Changes

- Fix Windows instances started with java -jar being incorrectly classified as IDE mode with online updates unavailable.
- JAR detection falls back from process arguments to XIANYU_ASSISTANT_JAR_PATH and the Spring Boot application location, while excluding classes directories.
- Self-restart falls back to the launcher of the current Java runtime when the process command is unavailable, avoiding a different Java version from PATH.

### Upgrade notes

- No new database migration. If an older Windows version cannot start an online update, manually replace the JAR and restart. This release fixes detection; locked-JAR replacement was hardened in v3.5.22.

### Verification and limits

- Historical records confirm updater tests and readiness detection for a Windows standalone JAR. This is not evidence of a full download/replacement/restart test on every platform.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.19) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.19) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.18...v3.5.19)

# Version 3.5.18

> Release: 2026-11-06

商品擦亮与控制台视觉更新。

### 主要变化

- 商品管理新增单品擦亮和一键擦亮在售商品，返回逐件结果；计划任务支持 Cron 定时擦亮，可指定商品范围。
- 统一深浅主题、玻璃面板、渐变按钮和导航状态，并适配减少动效与透明效果的系统偏好。
- AI 回复在配置延时上加入随机波动，最低 3 秒；修复前后端版本回退值不一致导致的显示和更新检查偏差。

### 升级说明

- 无新增数据库迁移。擦亮是否成功及调用限制以平台响应为准；延时随机化不保证避免风控或提升曝光。大批量擦亮的数量与超时保护在 v3.5.21 补充。

### 验证记录与边界

- 历史发布记录包含后端、前端 34 项测试、Lint、类型检查、生产构建及 Flutter 分析/测试通过；未将平台曝光或风控效果作为已验证结果。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.18) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.18) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.17...v3.5.18)

## English

> Release: 2026-11-06

Item polishing and console visual refresh.

### Changes

- Goods management adds single-item and bulk polishing with per-item results. Scheduled tasks support Cron-based polishing for selected items.
- Refresh light/dark themes, translucent panels, gradient buttons and navigation states, respecting reduced-motion and transparency preferences.
- AI replies add random variation to configured delays with a three-second minimum. Version fallbacks are aligned across the frontend and backend.

### Upgrade notes

- No new database migration. Polishing results and limits depend on platform responses; randomized timing does not guarantee risk-control avoidance or increased exposure. Large-batch limits and timeout protection were added in v3.5.21.

### Verification and limits

- Historical release records report backend checks, 34 frontend tests, lint/type/build checks and Flutter analysis/tests passing. Exposure gains and platform risk-control effects were not verified.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.18) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.18) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.17...v3.5.18)

# Version 3.5.17

> Release: 2026-11-04

定时自动更新与发货待复核。

### 主要变化

- 新增定时自动更新：默认启动 5 分钟后首检、每 6 小时检查官方 Release，按配置执行下载、校验、替换和重启。
- 自动发货结果 UNKNOWN 转为待人工复核，不再当作明确失败处理；复核状态保存失败时告警。
- 手动和自动更新共享版本检查逻辑；开发启动脚本兼容含空格的 JAR 路径，构建流程补充依赖与供应链检查。

### 升级说明

- 无新增数据库迁移。自动更新仅适用于独立 JAR；可通过 XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false 关闭。默认 exit 模式须配置 systemd 等进程管理器自动拉起，manual 模式需人工重启。
- 未知发货结果应先核对订单和聊天记录再决定是否重试；不能将“待复核”视为平台已确认失败。

### 验证记录与边界

- 历史记录覆盖自动更新调度开关、互斥、失败与超时分支，以及 UNKNOWN 发货结果和鉴权回归；跨版本自动安装仍取决于部署的进程管理与文件权限。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.17) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.17) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.16...v3.5.17)

## English

> Release: 2026-11-04

Scheduled updates and uncertain-delivery review.

### Changes

- Scheduled updates check official releases five minutes after startup and every six hours thereafter, then download, verify, replace and restart according to configuration.
- Unknown delivery outcomes enter manual review instead of being treated as confirmed failures, with warnings if review status cannot be persisted.
- Manual and scheduled updates share version-check logic. Development startup handles JAR paths with spaces, and build checks strengthen dependency and supply-chain handling.

### Upgrade notes

- No new database migration. Scheduled updates require a standalone JAR and can be disabled with XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false. Default exit mode needs a restarting supervisor; manual mode requires a manual restart.
- Review order and chat history before retrying an unknown delivery. Pending review does not mean the platform confirmed failure.

### Verification and limits

- Historical records cover scheduler switches, mutual exclusion, failures/timeouts, unknown-delivery handling and authentication regressions. Cross-version installation still depends on process supervision and file permissions.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.17) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.17) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.16...v3.5.17)

# Version 3.5.16

> Release: 2026-11-03

GitHub 发布迁移与任务租约保护。

### 主要变化

- 官方发布源迁至 yuchen0x1/Xianyu-Assistant 的 GitHub Releases，安装与更新使用完整 JAR、SHA-256 和 Ed25519 签名附件；保留历史分卷兼容入口。
- 任务调度增加租约围栏，过期执行者无法续期或覆盖已被接管的任务状态，降低异常恢复时重复执行的风险。
- 卡密状态区分预占待确认与已交付；已占用的旧数据按已交付保守回填，避免升级后被误释放。
- 轮换发布签名密钥并内置新验证公钥；统一服务端、前端和移动端版本，更新相关依赖及发布校验。

### 升级说明

- 相对 v3.4.10 新增 V3_25 卡密交付时间与 V3_26 任务租约迁移。升级前备份数据库和密钥文件；历史已占用卡密不会因升级自动重新入库。
- 旧版持有旧公钥或仅支持 Gitee 时，需从官方 GitHub 下载可信新包完成一次手动迁移；不要通过禁用签名校验绕过密钥轮换。

### 验证记录与边界

- 已核对标签代码、迁移文件与 GitHub 上现存的三个发布附件。历史记录包含后端及前端检查；本次未重新执行该旧版本或验证其跨版本安装。

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.16/XianYuSellerConsole-3.5.16.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.16/XianYuSellerConsole-3.5.16.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.16/XianYuSellerConsole-3.5.16.jar.sig)

Java 21；JAR 内置 Web 控制台，电脑和手机均通过浏览器访问。此 Release 无预构建 Docker 镜像，容器部署请从源码构建；独立移动端已停止维护并从当前分支移除。

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.16) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.16) · [迁仓后提交历史 / Post-migration history](https://github.com/yuchen0x1/Xianyu-Assistant/commits/v3.5.16/)

## English

> Release: 2026-11-03

GitHub release migration and job lease protection.

### Changes

- Official releases move to GitHub at yuchen0x1/Xianyu-Assistant, with a full JAR, SHA-256 and Ed25519 signature. Compatibility with historical split archives remains available.
- Job lease fencing prevents stale workers from renewing or overwriting jobs already claimed by another worker, reducing duplicate-execution risks during recovery.
- Card keys distinguish reservations awaiting confirmation from completed deliveries. Previously used keys are conservatively backfilled as delivered to prevent accidental release.
- Rotate the release signing key and embed its new verification key. Align backend, frontend and mobile versions and update dependencies and release checks.

### Upgrade notes

- Compared with v3.4.10, migrations V3_25 and V3_26 add card-key delivery timestamps and job lease fencing. Back up the database and keys; old used keys are not automatically returned to available inventory.
- Older clients using the old public key or Gitee-only source need a manual migration with a trusted package from official GitHub. Do not bypass key rotation by disabling signature verification.

### Verification and limits

- Tagged code, migrations and the three existing GitHub release assets were checked. Historical records include backend/frontend checks; this old version and its cross-version installation were not rerun.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.16) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.16) · [迁仓后提交历史 / Post-migration history](https://github.com/yuchen0x1/Xianyu-Assistant/commits/v3.5.16/)

# Version 3.4.10

> Release: 2026-09-24

- **修复：官方验证扫码更新重复轮询**：关闭扫码弹窗、重新生成二维码或切换会话后，旧请求结果会被忽略，避免后台继续请求已失效会话。
- **修复：扫码状态请求重叠**：同一二维码会话只允许一个状态请求在途，官方验证完成后重新扫码会立即使用新会话恢复轮询。
- **验证**：前端 32 项测试、TypeScript 类型检查、生产构建，以及后端连接恢复与扫码凭据相关 68 项测试通过。

# Version 3.4.9

> Release: 2026-09-07

- **安全：AI 服务端点 SSRF / 凭据外带修复（高危）**：`ai_base_url`/`ai_embedding_base_url` 此前可配置为任意 URL，服务端会把已保存的 AI API Key 以 Bearer 头发往该地址——持有 `settings:write` 的 OPERATOR 即可借服务端探测内网并外带 ADMIN 配置的密钥。现新增 `AiEndpointValidator` 三道防线：保存时校验（强制 https、拒绝回环/私网/链路本地/元数据端点）、ChatClient 与 VectorStore 构建出站前兜底校验（覆盖备份导入等绕过路径）、端点配置项仅 ADMIN 可修改。本地模型（ollama/LM Studio 等）经 `XIANYU_ASSISTANT_AI_EXTRA_ALLOWED_HOSTS` 显式放行。
- **安全：发布物 Ed25519 签名体系上线（供应链加固）**：此前自更新与安装脚本只校验 SHA-256，而校验文件与安装包同源，发布通道被入侵时两者可被同时替换。现新增 `scripts/sign-release.sh` 签名工具与 `ReleaseSignatureVerifier`（JDK 原生 Ed25519）：官方渠道更新**强制验签**（`app.self-update.signature-required=auto`），缺签名/验签失败一律拒绝更新；`install.sh` 同步验签；`publish-gitee.sh` 集成签名且官方仓库未配置签名私钥时拒绝发布（fail-closed）。签名私钥仅存 CI Secret/离线（`RELEASE_SIGN_PRIVATE_KEY`），客户端只内置验证公钥；社区 Fork（自建发布源）默认不强制，保持开箱可用。密钥轮换经 `XIANYU_ASSISTANT_RELEASE_PUBLIC_KEY`。
- **安全：发布脚本 GITEE_TOKEN 传递方式修复**：`publish-gitee.sh` 此前把令牌拼进 URL query（GET，会进代理/访问日志）与 multipart 表单（进进程列表），现全部改为 `Authorization: Bearer` 请求头传递。
- **安全：初始账号口令策略与爆破防护**：注册口令最小长度 5 → 8（前端注册表单同步）；注册接口此前完全不限流且首注册者即 ADMIN，现与登录共用严格档位（5 次/10 分钟/IP），`checkUserExists` 增加 60 次/10 分钟宽松限流挡脚本化探测。
- **安全：会话有效期 30 天 → 7 天 + Token 轮换续期**：新增 `POST /api/login/refresh` 轮换端点（旧 Token 立即撤销，过期必须重新登录）；前端活跃使用期间每 12 小时静默续期（并发单飞），任意请求遇 401 先静默续期一次并重放原请求，失败才跳转登录页——活跃用户无感知，非活跃会话 7 天后自动收紧。
- **安全：反向代理下限流按真实 IP 生效**：登录限流原取直连地址，nginx 反代后所有用户共享一个限流桶。新增 `app.security.trusted-proxies`（环境变量 `XIANYU_ASSISTANT_TRUSTED_PROXIES`）：仅当直连地址为受信代理时才采信 `X-Real-IP`/`X-Forwarded-For` 最右一跳，防头伪造绕过限流。
- **测试**：新增 AI 端点校验器（11 例）、发布签名验证（5 例，openssl↔Java 互操作实测）、会话轮换与分档限流（4 例）、签名强制模式判定（1 例）、前端 401 静默续期重放（2 例）；全量后端、前端与类型检查全绿。架构说明见 `docs/security-architecture-2026-09.md`。

# Version 3.4.8

> Release: 2026-09-07

- **修复：在线更新下载进度条恒为 0%**：Gitee Release API 返回的资产对象只有 name/browser_download_url，不含 size 字段（与 GitHub 不同），下载进度分母一直为 0，百分比计算恒为 0%，大小文案也无法显示——下载实际在正常进行，仅进度不可见。现下载前用 HEAD 请求跟随重定向探测各分卷真实大小（15 秒超时，探测失败自动退化为无百分比进度、不阻断更新），进度条与「已下载/总大小」文案恢复正常。

# Version 3.4.7

> Release: 2026-09-07

- **修复：闲鱼瞬时故障被误判为「Cookie过期」并误发告警邮件**：Token 接口偶发短暂返回 SESSION_EXPIRED（此时 hasLogin 登录态仍有效，同一 Cookie 数十秒后自愈，生产日志实测 18~41 秒），旧逻辑在约 6 秒内即宣布「Cookie 已彻底过期」：账号标记过期、误发过期邮件、触发 120 秒认证熔断，诱导用户不必要地重新扫码。现宣布失效前先做最终确认（最多 3 轮、每轮 20 秒的间隔探测），登录态仍有效时继续探测，Token 恢复即取消过期判定；只有登录态确认消失或探测全部失败才进入告警流程。
- **修复：恢复链异常处理缺陷导致一次故障重复计 3 次失败**：恢复链中层 catch(Exception) 吞掉内层已抛出的 CookieExpiredException 后重入致命路径，一次事故写出 3 条重复失败日志、累计 3 次认证失败计数（恰好触发阈值进入熔断）。现显式透传该异常，一次故障只记录一次。
- **修复：浏览器兜底刷新冷却被当作恢复失败**：30 分钟冷却期内浏览器兜底被跳过并返回 false，与真实刷新失败无法区分，导致刚刷新成功的 Cookie 被误判为无法恢复。现冷却期内跳过浏览器步骤直接重试 Token，不再中断恢复链。

# Version 3.4.6

> Release: 2026-09-07

- **修复：更新弹窗「更新内容」显示原始 Markdown**：Gitee Release 描述为 Markdown 源码，弹窗此前按纯文本渲染，加粗星号（\*\*）、引用符（>）等标记全部裸露、排版混乱。现展示前自动清洗：去掉加粗/标题/引用/行内代码标记，过滤与「发布时间」栏重复的 Release 行，保留列表与换行；纯文本方案不引入渲染依赖，也无 XSS 面。
- **修复：「关于」页品牌图占满整页**：about 系列页面样式自初始版本起缺失，品牌 SVG 无尺寸约束直接按原始尺寸渲染，挡住页面全部内容。现补齐关于页整套样式（品牌图 64px 圆角、头部信息卡、双列工作流卡片、三步引导、使用提醒、页脚），窄屏自动单列，深浅色主题均已适配。
- **修复：在线更新停在「新版本已就绪」不自动重启**：重启此前仅由弹窗的下载进度轮询触发，下载期间关闭弹窗或刷新过页面后会永久停留在就绪状态，且再点「立即在线更新」会被后端以「已有更新进行中」拒绝。现在打开更新弹窗时若检测到新包已就绪，自动续跑重启与探活流程并刷新页面，全程无需人工干预。

# Version 3.4.5

> Release: 2026-09-07

- **修复：自定义 AI Base URL 拼出错误端点（404）**：Spring AI 默认在 Base URL 后固定追加 /v1/chat/completions，配置智谱（https://open.bigmodel.cn/api/paas/v4）等带版本号的接口地址时会被拼成 /v4/v1/chat/completions 导致测试连接与对话全部 404。现按 Base URL 末段自动推导接口路径：以版本号结尾（v1/v2/v4…）时拼接 /chat/completions、/embeddings、/models；不带版本号时保持 /v1/* 不变，继续兼容 DashScope compatible-mode、OpenAI 等；同时容忍尾部多余斜杠以及直接粘贴完整 /chat/completions 端点的写法。AI 对话、发货文案润色、向量 Embedding、模型列表获取四处统一修复。
- **前端：AI Base URL 输入框补充提示文案**，说明支持带版本号的接口地址（如智谱 v4），无需以 /v1 结尾。

# Version 3.4.4

> Release: 2026-09-06

- **修复：发货记录静默丢失（数据丢失级）**：文本自动发货路径的 pnm_id 恒写空串，与 (账号, pnm_id) 唯一索引冲突，同一账号第二笔发货记录插入即失败且被静默吞掉。现按"消息 pnmId → 订单号派生键 → 随机键"三级生成，同订单重复记录仍被索引拦下（幂等），不同订单不再冲突。
- **修复：API 兜底发货阻塞调度线程池**：兜底轮询含 20-30 秒防风控延迟，此前在 @Scheduled 线程内同步执行，会饿死备份、Token 刷新、连接看门狗等定时任务。现改为每账号提交全局异步池执行，并用 in-flight 集合防同账号重叠。
- **修复：文本发货缺失会话 ID 时记录失真**：sId 缺失不再抛 NPE 被兜底 catch 记成模糊异常，而是明确记录失败原因。
- **前端：lint 存量清零（92 → 0）**：清理全部未使用导入/变量/死代码，23 处显式 any 按实际用途类型化；订单详情弹窗补齐嵌套类型；模板补 3 处缺失的可选链。

# Version 3.4.3

> Release: 2026-09-06

- **本次为清理后的干净重发布版**（代码与 v3.4.2 一致），原日志称其为当时唯一保留的 Release，并曾下架更早版本；此处仅保留当时记录，不代表当前 GitHub Release 状态。包含的关键修复与改进：
- **升级完成后自动重启（restart-mode=self）**：裸 `nohup java -jar` 部署升级后以相同命令行生成新进程接管服务，无需 systemd/Docker，不再"升级即停机"；握手机制避免端口竞争，自拉起失败保持旧进程运行。
- **修复在线更新「下载地址主机不受信任: foruda.gitee.com」**：Gitee 附件下载 302 到官方存储网关被白名单拦截的问题，信任边界放宽为 Gitee 域名及其子域（严格标签边界，伪造后缀仍拒绝）。
- Webhook 有界队列：端点全面变慢时不再无限堆积内存，队列打满转后台重投兜底。
- Android 正式签名加固：不再以 debug 密钥冒充 release 包；移动端强制 HTTPS。
- 发布链路自动化：构建、SHA-256、分卷上传、更新日志写入、旧附件清理（配额 1GB 内自动保留最近版本）一条命令完成。

# Version 3.4.2

> Release: 2026-09-06

- **新增 restart-mode=self 进程自拉起**：裸 `nohup java -jar` 部署（无 systemd/Docker/install.sh 循环等进程管理器）此前在 `exit` 模式下升级即停机。现支持 `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`：升级替换完成后以相同命令行生成新进程接管服务，旧进程随即退出；新进程启动最早期通过 `xianyu.restart.waitForPid` 握手等待旧进程释放端口，避免端口竞争。自拉起失败时保持旧进程运行并提示手动重启，不会造成停机。
  - 默认仍为 `exit`（由 install.sh 循环/Docker/systemd 拉起）；`self` 与外部循环/容器重启策略同时启用会端口竞争，Docker 镜像已显式固定 `exit`。
  - 前端升级弹窗原本就会在替换完成后自动触发重启并探活刷新，配合 `self` 模式实现全程无人工干预的在线升级。

# Version 3.4.1

> Release: 2026-09-06

- **修复在线更新「下载地址主机不受信任: foruda.gitee.com」**：Gitee Release 附件下载会 302 到官方附件存储网关 `foruda.gitee.com`，v3.3.24 起的下载主机白名单只认 `gitee.com`，导致自更新必失败。现将信任边界放宽为 Gitee 自有域名及其子域（严格标签边界，`gitee.com.evil.com` 等伪造后缀仍被拒绝），测试补齐真实跳转链与绕过用例。
- ⚠️ v3.3.24 / v3.4.0 实例的自更新器自带此缺陷，无法在线更新到本版本，请通过 Release 页下载分卷 JAR（拼接后校验 SHA-256）或重新执行 `install.sh` 手动升级一次；此后在线更新恢复正常。

# Version 3.4.0

> Release: 2026-09-06

- 统一版本源、Gitee 发布链路和移动端正式版本号。
- 收紧自更新、迁移校验、Webhook 调度和移动端网络安全边界。

# Version 3.3.24

> Release: 2026-09-05

- **彻底移除 GitHub 更新通道，在线更新只走 Gitee**：仓库已冻结于 GitHub、新版本仅发布到 Gitee，旧版自更新"GitHub 主通道不容错"的逻辑导致 v3.3.23 在线更新报「GitHub Release 查询失败，HTTP 404」而卡死。本次变更：
  - 版本检查（`checkUpdate`）删除出口 IP 归属地探测与 Gitee/GitHub 双通道竞速，固定请求 Gitee Release API；响应 `channel` 恒为 `gitee`。
  - 自更新（`SelfUpdateService`）资产解析只查 Gitee（整包 → 分卷 → `.sha256`）；下载主机白名单收紧为仅 `gitee.com`（GitHub 系域名不再受信任）；未配置 `gitee-repository` 时明确报错。
  - 删除私有 GitHub 仓库令牌逻辑及配置项 `app.update-check.repository`、`app.update-check.token`（环境变量 `XIANYU_ASSISTANT_UPDATE_REPOSITORY`、`XIANYU_ASSISTANT_UPDATE_TOKEN` 不再使用，无需设置）。
  - `install.sh` 移除下载源选择菜单与 GitHub 回退，固定从 Gitee 获取最新版本；脚本头部下载地址改为 Gitee raw。
  - 前端版本更新弹窗移除 GitHub 通道展示分支，「更新通道」固定显示 Gitee。
  - 修复 404 误导文案：提示改为「该版本未在 Gitee 发布 Release 或仓库不可访问」「请确认已在 Gitee 发布该版本的 Release 及附件」。
- 测试同步：删除 GitHub 通道/令牌/探测白名单相关用例，新增「GitHub 发布主机不再受信任」「channel=gitee」断言。

# Version 3.3.23

> Release: 2026-09-05

- **在线更新源 Gitee 化 + 分卷 JAR 支持**：自更新与 `install.sh` 现已支持从 Release 附件直接获取 JAR 分卷（命名 `{JAR名}.jar.split-00/01/…`，绕开 Gitee 单附件 100MB 上限）：按编号升序逐卷下载 → 拼接 → 仍以完整 JAR 的 `.sha256` 强校验 → 原子替换。Gitee 分卷优先于 GitHub 历史通道；分卷编号缺卷即拒绝更新，宁可不动也不拼出不完整包。`install.sh` 同步支持分卷下载拼接（Gitee 优先、GitHub 兜底）。
- 新增测试：分卷序列选择（连续性校验/缺口拒绝）、分卷拼接摘要等价性。

# Version 3.3.22

> Release: 2026-09-05

- **自动回复决策日志**（参考 xianyu-super-butler 的回复决策日志设计）：新增 `auto_reply_decision` 表（迁移 `V3_23`），记录买家消息未被自动回复的可追溯原因——人工接管（`HUMAN_TAKEOVER`）、商品未同步（`GOODS_NOT_FOUND`）、无可用回复策略（`NO_REPLY_STRATEGY`）、策略未生成内容（`NO_REPLY_CONTENT`）；同会话同原因 60 分钟去重，写入失败只告警不阻断主流程。新端点 `POST /api/items/autoReplyDecisions`，自动回复页新增「未回复记录」弹窗（原因/账号筛选 + 分页）。决策日志默认保留 90 天（`XIANYU_ASSISTANT_DECISION_RETENTION_DAYS` 可配，0=不清理），每天凌晨自动清理超期记录。
- **修复滑块验证码异常被误判为登出的隐患**：全局异常处理器原先把 `CaptchaRequiredException` 映射为裸 `code=401` 响应，前端拦截器对 401 会清除 token 并跳转登录页——一旦有端点漏捕获该异常，用户会被误登出。现统一返回业务码 `1001`（滑块验证码契约，与 WebSocketController 一致），并新增 standalone MockMvc 集成测试验证异常契约经真实 MVC 层的最终 JSON 形状。
- **参数校验错误码语义修正**：`@Valid` / `@Validated` 校验失败的业务码从 `404` 修正为 `400`（Bad Request），消除"参数写错=资源不存在"的语义混淆；响应消息不变，前端展示不受影响。
- **权限补齐**：`/api/qrlogin/*`（扫码生成/状态/清理会写入账号登录凭据）补上 `account:write` 权限注解，前端 `/qrlogin` 路由守卫同步对齐。
- **前端网络层幂等重试**：GET/HEAD 请求在网络不可达、超时或 502/503/504 网关故障时自动重试最多 2 次（短退避），业务码错误、4xx 与用户主动取消不重试，错误提示仍只弹一次；支持按请求 `retry: false` 关闭。
- **设置页巨型文件拆分（第一步）**：`settings/index.vue` 从 4372 行降至 2299 行——样式整体外移为 `settings.css`（全局命名空间类，无跨页冲突，`:global` 包装已简化），关于页、API 兜底发货、代理池三个自包含面板拆分为独立组件并配渲染回归测试。
- 前端清理脚手架遗留死代码（未引用的 `stores/counter.ts`、`assets/` 旧样式/logo）与死导入（`getApiDeliveryFallbackStatus`/`updateApiDeliveryFallback`/`getNotifyDesc`）；`views/**/index.vue` 豁免多词组件名 lint 规则；自动回复页清理 16 个存量 lint 错误（未使用解构 + `any` 注入类型）。

# Version 3.3.21

> Release: 2026-09-04

- **在线一键更新**：版本更新弹窗新增「立即在线更新」。更新流程 = 下载新版 JAR → SHA-256 逐字节校验（与发布校验文件不符即放弃）→ 备份并原子替换 → 按重启策略重启 → 前端自动等待恢复并刷新。JAR 走 GitHub 发布源（超出 Gitee 附件上限），私有仓库复用更新令牌下载；校验文件优先取 Gitee。下载主机白名单 + 手动重定向逐跳校验 + 跨主机剥离 Authorization，防令牌泄漏与 SSRF。
- 重启策略可配（`app.self-update.restart-mode`）：`exit`（默认，交给 systemd/守护循环拉起）、`command`（执行自定义命令）、`manual`（只替换人工重启）。install.sh 同步改为守护循环启动，进程退出 2 秒后自动拉起，配合在线更新。
- 仅以独立 JAR 方式运行时可用；IDE 开发模式自动不可用。触发需 `settings:write` 权限。
- 修复自动回复延时任务相关 2 个存量测试失败（JobEngine `createJob` 已升级为 8 参签名，测试未跟随）；全量测试首次全绿。

# Version 3.3.20

> Release: 2026-09-04

- 阶段 3.2 智能议价（商品级策略 + 会话级轮次跟踪）：议价决策由代码计算（保底价/让价步长/最大轮数），AI 仅负责措辞；买家出价 ≥ 保底价自动同意，超出轮数坚持底价，AI 不可用时降级为确定性话术模板，策略绝不落空。
- 议价意图与出价识别为纯本地正则实现（`50块能拿吗`、`便宜到100`、`少20` 等句式），不产生额外 AI 调用；议价会话按 (账号, 会话) 幂等跟踪轮次，空闲 48 小时或切换商品自动重置。
- 新增 `V3_22` 迁移：`xianyu_goods_config` 议价策略字段 + `bargain_session` 会话表；自动回复页 AI 配置区新增"智能议价"开关与策略配置（开启需先填保底价）。
- 修复 AI 回复错误占位文案（"AI服务暂未配置"等）可能被当作回复内容发给买家的问题：错误占位现在按失败处理，不再生成回复。
- 修复全新空库首次启动失败：数据库初始化顺序调整为"先建 schema.sql 基础表、再应用版本迁移"。旧顺序下迁移语句依赖尚未创建的基础表（如 V3_3 写入 `xianyu_sys_setting`）导致新装部署无法建表；新顺序对旧库升级路径无影响（基础表已存在时建表步骤自动跳过，迁移器本就容忍 schema.sql 预建出的重复列）。
- 通知渠道支持"发送测试"：新增 `/api/setting/testNotify`，设置页六个渠道（企业微信/钉钉/Server酱/Bark/Telegram/ntfy）均可一键验证配置。

# Version 3.3.19

> Release: 2026-09-02

- 阶段 3.1 自动评价与求好评：新增按账号配置评价模板、延迟调度、人工确认/取消/重试、评价记录与 `/reviews` 页面。
- 新增 `review_rule` / `review_record` 及 `review:read`、`review:write` 权限；评价任务接入统一 JobEngine 并保持订单级幂等。
- 平台自动评价接口尚未接入时，Worker 明确转为 `MANUAL_REQUIRED`，不伪造提交成功。

# Version 3.3.18

> Release: 2026-09-02

- 阶段 2 可靠性闭环：Webhook 投递历史（outbox）持久化与指数退避重试，业务事件（发货/订单/连接/告警/卡密）统一接入。
- 告警持久化与监控中心：`alert_event` 去重/抑制窗口/发送成功后推进、ACK/SILENCE/RESOLVE 状态机、`/monitoring` 页面（概览 + 告警列表 + 操作）。
- 通知渠道相互独立，单渠道失败不再阻塞其他渠道并记录失败计数。
- 敏感设置（AI Key、SMTP 密码）与 Webhook secret 加密落库（AES-GCM），响应与备份默认不泄密。
- 备份补齐：订单、聊天记录（脱敏）、站内通知、任务/监控状态、发布历史 5 个 handler；`includeSecrets=false` 默认导出不含明文 Cookie/Token。
- 数据库迁移 `V3_15/V3_16/V3_17`（通知用户范围、任务幂等 lease、告警+Webhook outbox），`schema.sql` 补齐缺失业务表，新库建全量表、旧库迁移升级。
- 后端测试 429 通过，前端单测 23 通过、type-check 与构建通过；完整执行记录见 `docs/upgrade-execution-2026-09.md`。

# Version 3.3.17

> Release: 2026-08-21

- 新增多账号接入与并行验证操作手册：`docs/multi-account-onboarding-2026-08-21.md`。
- 每个账号使用独立本机浏览器 profile 登录、Cookie 写入后台、触发连接并验证在线；禁止混用 profile。
- 完成真实双账号接入验证：两个账号同时保持 WebSocket 在线，跨账号消息收发正常，账号凭据相互独立，无覆盖、无掉线。

# Version 3.3.16

> Release: 2026-08-21

## 主要变化

- 统一浏览器请求头，与当时核对的 Chrome 147 请求指纹一致。
- 新扫码凭据保存后等待 15 秒再请求 WebSocket Token，使平台会话有时间生效。
- 移除后端重连后前端立即重复发起的 Token 请求。
- 使用官方 App 验证及扫码更新指引替换过时的同会话滑块提示。

# Version 3.3.15

> Release: 2026-08-21

## 主要变化

- 扫码确认后完成官方 Passport login_token 交换，不再保存轮询阶段临时 Cookie。
- 二维码生成、轮询、票据交换及 H5 刷新保持同一 Cookie 会话。
- 保存账号前刷新 _m_h5_tk、unb 等认证 Cookie。
- 需要手机或安全验证时继续引导官方操作，不包含绕过。

# Version 3.3.14

> Release: 2026-08-21

## 主要变化

- 使用官方闲鱼 App 扫码流程刷新 Cookie/Token，替换失效的服务端滑块恢复路径。
- 平台验证待处理时停止自动清除状态与反复轮询。
- 引导用户先在官方 App 完成手机或安全验证，再恢复连接。

# Version 3.3.13

> Release: 2026-08-21

## 主要变化

- 平台返回 M4HRm 等错误页时立即结束远程验证会话。
- 会话结束后清空失效截图并拒绝后续鼠标事件。
- 向前端返回真实 active 状态，避免过期页面仍可交互。

# Version 3.3.12

> Release: 2026-08-21

## 主要变化

- 平台返回 T73mam 等错误页后停止自动刷新验证链接。
- 增加五分钟账号级冷却，避免反复 Token 探测。
- 冷却期间禁用远程验证重试，并显示倒计时。

# Version 3.3.11

> Release: 2026-08-21

## 主要变化

- 验证会话过期或重新打开时清除旧截图。

# Version 3.3.10

> Release: 2026-08-21

## 主要变化

- 避免把初始风控 Cookie 变化误判为滑块完成。
- 刷新通用 error:xxxx 验证错误页，并清除远程弹窗的旧截图。

# Version 3.3.9

> Release: 2026-08-21

## 主要变化

- 打开 Playwright 前检测过期挑战链接。
- 在同一会话内处理失效的 error:Lxvm 验证页面。

# Version 3.3.8

> Release: 2026-08-21

## 主要变化

- 修复失效远程会话返回空白验证窗口的问题。
- 重新打开已关闭的窗口时立即创建新会话。

# Version 3.3.7

> Release: 2026-08-21

## 主要变化

- 修正远程验证视口坐标映射，并按顺序处理拖动事件。

# Version 3.3.2

> Release: 2026-08-20

## 新增

- 计划任务管理：支持 Cron 定时发布、下架、同步商品，支持启停、立即执行、失败自动暂停和风控恢复。
- 版本元数据与 GitHub Release 流程同步更新。

# Version 3.3.1

> Release: 2026-08-13

## 主要变化

- 统一版本号并准备发布；原记录没有列出新增业务功能。

# Version 3.3.0

> Release: 2026-08-02

## 新增

- **凭据加密落库（A3.1）**：新增 `CredentialCipher`（AES/GCM，密钥来自环境变量 `XIANYU_ASSISTANT_CREDENTIAL_SECRET` 或 `dbdata/credential-secret.key`）与 MyBatis `EncryptedStringTypeHandler`，`XianyuCookie` 的 cookieText / mH5Tk / websocketToken 三字段加密落库，兼容历史明文数据。
- **操作审计强化（A4.1）**：限流触发记录 `RATE_LIMIT` 审计事件；商品发布/下架成功与失败审计；操作日志类型新增 `RATE_LIMIT` / `PUBLISH` / `OFF_SHELF`。
- **命令中心与 MCP 开放能力（F7.1）**：命令注册表（publish_item / off_shelf / send_message / sync_goods / list_goods / list_accounts）+ `POST /api/commands/list` 与 `/api/commands/execute` 接口 + 轻量 MCP JSON-RPC 端点 `POST /api/mcp`（initialize / tools/list / tools/call）+ 前端「命令中心」页面。

# Version 3.2.0

> Release: 2026-08-02

## 新增

- **商品价格历史（F6.1）**：新增 `xianyu_goods_price_history` 价格快照表（V3_4 迁移），商品刷新/保存时记录价格快照（同一天去重）；新增 `POST /api/price-history/trend` 价格趋势查询接口；商品详情弹窗新增价格趋势折线图（最高/最低/当前价统计）。
- **数据面板统计缓存优化（P2.1）**：`DashboardStatsService` 接入 Caffeine 本地缓存（30 秒 TTL，key 前缀 `dashboard:stats:`），降低 SQLite 查询压力。
- **统一空状态组件 EmptyState**：新增通用空状态组件，商品表格/详情弹窗空态替换，商品视图暗色模式补漏。
- **性能索引评审（P1.1）**：V3_5 迁移记录，确认三处高频查询索引已在 schema.sql 定义（本迁移仅作版本标记）。

# Version 3.1.0

> Release: 2026-08-02

## 新增

- **商品发布/下架闭环（mtop v1.0/v1.1）**：全新「商品发布」页支持 AI 类目识别、图片上传闲鱼 CDN、发布 API（`mtop.idle.pc.idleitem.publish` v1.0）与下架 API（`com.taobao.idle.item.delete` v1.1），结果自动落库 `xianyu_publish_history` 并提供历史查询与商品 ID 回填。
- **写操作限流熔断（令牌桶 + RGV587 风控分级熔断）**：新增 `@RateLimit` AOP 切面 + 令牌桶限流器（按账号 + 桶隔离，发布/下架默认每分钟 1 次），触发限流返回 429 友好提示；`XianyuAuthFailureGuard` 升级，RGV587 / 被挤爆啦 / FAIL_SYS_USER_VALIDATE 等明确风控错误立即进入 300 秒熔断，普通认证失败维持 3 次/60s 阈值。
- **数据库版本化（schema_version 表 + V3_1/V3_2/V3_3 迁移脚本）**：新增 `schema_version` 表 + `SchemaMigrator` 迁移执行器（`db/migration/V*__*.sql` 编号脚本，启动时按序应用并记录版本与 SHA-256 校验和）；包含 `V3_1__publish_history.sql`（发布历史表）、`V3_2__schedule_task.sql`（定时任务表）、`V3_3__proxy_pool_setting.sql`（代理池种子配置）三个迁移。
- **定时任务中心（ThreadPoolTaskScheduler + CronTrigger，支持失败自动暂停 + guard 恢复）**：基于 Spring `ThreadPoolTaskScheduler` + `CronTrigger` 动态调度，支持 `SYNC_GOODS`（定时同步商品）与 `OFF_SHELF`（定时下架）任务；连续失败 3 次自动暂停，由 guard 任务恢复；应用启动时重载所有启用任务。
- **代理池轮换（多代理轮询 + 黑名单 TTL + 自动回退直连）**：多代理配置 + 轮询轮换 + 网络层失败黑名单（TTL 自动恢复）+ 失败重试切换；支持代理用户名密码认证；全部代理在黑名单中时自动回退直连；前端设置页新增「代理池」面板（总开关 / 代理列表 / 黑名单 TTL / 最大重试次数）。
- **通知渠道扩展（Bark / Telegram / ntfy）**：新增 `BarkNotifier`、`TelegramNotifier`、`NtfyNotifier` 三个通知渠道（策略模式），`NotifyService` 自动注册新渠道；前端设置页「通知渠道」新增 Bark / Telegram / ntfy 配置项。

## 变更

- `HttpClientUtils.postWithHeaders` 改造集成代理池（默认关闭，配置开启即生效，无需重启）。
- `SysSettingServiceImpl` 中代理相关配置变更触发代理池热重载（仿 `AI_RELATED_KEYS` 模式）。

# Version 3.0.0

> Release: 日期未记载（历史文档未提供，未推算）

## 新增

- **商品发布（卖家运营闭环）**：全新「商品发布」页（工作台 → 商品发布），支持 AI 类目识别（`mtop.taobao.idle.kgraph.property.recommend`）、标题/描述/价格/运费/数量填写、图片上传闲鱼 CDN（复用既有 `ImageUploadService`）、发布 API（`mtop.idle.pc.idleitem.publish`）与下架 API（`com.taobao.idle.item.delete`），发布/下架结果自动落库 `xianyu_publish_history` 并提供历史查询与商品 ID 回填。
- **写操作限流护栏**：新增 `@RateLimit` AOP 切面 + 滑动窗口限流器（按账号 + 桶隔离，默认发布/下架每分钟 1 次），参考 goofish-cli 令牌桶限流思路；触发限流返回 429 友好提示。
- **RGV587 风控分级熔断**：`XianyuAuthFailureGuard` 升级——RGV587 / 被挤爆啦 / FAIL_SYS_USER_VALIDATE 等明确风控错误立即进入 300 秒熔断（不再等待 3 次阈值），普通认证失败维持原 3 次/60s 阈值逻辑。
- **数据库 Schema 版本化**：新增 `schema_version` 表 + `SchemaMigrator` 迁移执行器（`db/migration/V*__*.sql` 编号脚本，启动时按序应用并记录版本与 SHA-256 校验和）；首个迁移 `V3_1__publish_history.sql` 创建发布历史表。
- **统一版本源**：版本号以 `pom.xml` 为单一来源（3.0.0），构建期生成 `version.json`（version + buildTime），README / 前端 `package.json` 同步为 3.0.0。
- **API 协议版本支持**：`XianyuApiUtils` / `XianyuApiCallUtils` 支持按 API 指定 mtop 协议版本号（发布 v1.0 / 下架 v1.1 / 类目 v2.0 / 地址 v1.0），旧调用完全兼容。

## 优化

- `XianyuProtocolProfile` 集中新增卖家运营 API 常量（发布/下架/类目/地址/图片上传）。

## 测试

- 更新 `XianyuApiCallUtilsOutcomeTest`（适配 API 版本重载）、`XianyuAuthFailureGuardTest`（新增 RGV587 分级熔断用例）；全量回归 `Tests run: 292, Failures: 0, Errors: 0`，前端 type-check + build 通过。

# Version 2.2.0

> Release: 日期未记载（历史文档未提供，未推算）

## 新增

- **多平台消息通知**：新增企业微信、钉钉、Server酱三个通知渠道，配合原有邮件实现多渠道告警。在「系统设置 → 通知渠道」中配置 Webhook/SendKey 后，掉线、Cookie 过期、卡密预警/耗尽、发货失败、风控验证等事件会同时推送到所有已配置渠道。
- **站内通知中心**：右上角新增通知铃铛，展示系统通知（含未读角标、级别标识、全部已读），通知同时落地到数据库 `xianyu_notification` 表（启动时自动建表）。
- **发货记录 CSV 导出**：发货记录页新增「导出」按钮，按当前筛选条件导出全部记录（含 BOM，Excel 直接打开不乱码）。
- **操作日志 CSV 导出**：操作日志页新增「导出」按钮，按当前筛选条件导出全部记录。
- **商品统计排行**：数据面板新增「成交 TOP 商品」与「咨询 TOP 商品」两个排行榜卡片，展示热门商品（订单数/消息数 + 成交金额），带前三名奖牌色序号和进度条。
- **批量确认发货**：发货记录页支持多选记录（表格复选框 / 移动端卡片复选框、全选本页），一键批量调用闲鱼确认接口，逐条反馈成功/失败明细。
- **批量配置自动化**：自动发货页支持「批量配置」模式，多选商品后一键开启/关闭自动发货、自动回复（单次最多 200 个商品），逐条反馈成功/失败明细。
- **多账号对比**：数据面板新增「账号对比」卡片，按日期汇总各账号订单数、发货成功/失败、AI 回复数与确认销售额，顶部展示全账号合计。
- **配置模板批量套用**：自动发货页批量模式新增「套用配置」，选择一个参考商品后一键将其基础配置（自动发货开关、发货内容、卡密配置、发货图片、自动确认发货、发货延迟等）复制到多个目标商品（单次最多 200 个，自动排除参考商品自身并去重）。
- **消息会话中心**：消息页新增「会话」视图，按买家会话聚合展示（最新消息预览、时间、待回复标记、消息数），支持按买家/内容关键词搜索；点击会话查看完整聊天上下文并可直接在页面快速回复买家（走 WebSocket 发送，自动记录手动回复并触发人工接管）。
- **关键词命中排行**：数据面板新增「关键词命中 TOP」卡片，按日期聚合各自动回复关键词的命中次数、成功/失败/待回复数与涉及买家数，帮助优化关键词回复规则配置。
- **操作健康度**：数据面板新增「操作健康度」卡片，按日期聚合各操作类型（自动发货、自动回复、Token刷新、Cookie更新等）的总体成功率与失败数，并展示各类型成功率、平均耗时，便于快速定位系统异常。
- **失败原因 TOP**：数据面板新增「失败原因 TOP」卡片，按日期聚合最常见的操作失败原因（如触发风控、Cookie 无效、连接异常等）与涉及的操作类型，直接指向问题根因。

## 优化

- **日志自动清理**：日志滚动策略升级为「按日期分目录 + 按大小滚动 + 过期自动删除」，默认保留 30 天（可用环境变量 `LOG_MAX_HISTORY_DAYS` 调整），并设置总量上限，避免长期运行磁盘占满。
- **代码质量**：工具类统一使用 Spring 管理的单例 `ObjectMapper`（新增 `ObjectMapperProvider`）；合并 `CookieUtils` 与 `XianyuSignUtils` 的重复逻辑；补全前端通用类型定义（分页、通知、设置等）。
- **线程安全**：修复 `PlaywrightManager` 浏览器初始化在锁外的并发问题。
- **单元测试**：新增 `XianyuSignUtilsTest`（Cookie 解析/签名）、`NotificationServiceImplTest`（站内通知 CRUD），全量回归通过。

## 安全

- **SQL 注入防护**：`DatabaseInitListener` 对动态表名/列名增加白名单正则校验（`[A-Za-z_][A-Za-z0-9_]*`），拒绝非法标识符拼接入 SQL，覆盖建表校验、表存在性检查、列补齐等路径。

# Version 2.1.11

> Release: 日期未记载（历史文档未提供，未推算）

## 优化
- 统一自动发货三条链路的行为说明：前台 WebSocket 事件/手动触发在断线时自动退回虚拟发货 API；后台 `ApiDeliveryScheduler` 作为额外可选轮询兜底，默认关闭。
- 补充关键代码注释，明确 `executeDelivery(...)` 的 API 兜底不依赖后台调度器，避免把两层机制混为一个“默认总兜底”。

## 新增

- 连接页从闲鱼验证页返回前台时自动确认滑块完成状态，并尝试恢复 WebSocket 连接。
- 新增用户确认滑块后的强制 Token 探测入口，绕过本地短期等待保持，直接确认平台是否已经放行。

## 优化

- “已完成，恢复”不再先清空等待状态，而是先探测平台、再恢复连接、最后更新状态。
- 滑块引导文案改为“完成滑块后返回本页自动确认恢复”，减少不必要的 Cookie 更新操作。

## 修复

- 修复用户已在闲鱼完成滑块验证后，后台仍显示“等待滑块验证”的问题。
- 修复手动恢复可能假成功、随后又跳回等待验证的问题。
- 修复手动确认后仍被 5 分钟滑块保持状态挡住，导致没有真实探测平台放行结果的问题。

# Version 2.1.10

> Release: 日期未记载（历史文档未提供，未推算）

## 新增

- 独立滑块/风控邮件开关 `email_notify_captcha_enabled`，只有真实投递成功才去重。
- 连接页自动回复状态区分“已开启”和“已开启，当前暂停”。
- 滑块等待期间的递增退避低频探测：约 30 分钟、1 小时、2 小时、4 小时、6 小时封顶。

## 优化

- WebSocket Token 只按数据库真实过期时间提前 30 分钟刷新，健康连接不再每小时重启。
- `WebSocketServiceImpl` 成为 websocket_token 自动到期刷新的唯一调度所有者。
- 页面回前台刷新改为失活/恢复周期去重，并忽略过期响应和卸载后响应。
- 自动回复文字发送改为服务端确认结果；未知结果不盲目重发。

## 修复

- 修复滑块后每 5 分钟继续撞风控接口的问题。
- 修复 `FAIL_SYS_USER_VALIDATE` / RGV 被误判为 Cookie 失效的问题。
- 修复“被挤爆啦”被误标为滑块或 Cookie 失效的问题。
- 修复获取替代 Token 失败时清空原过期时间并切断仍可用连接的问题。
- 修复握手成功但未收到 `reg-sid` ACK 仍被当作在线的问题。
- 修复滑块邮件分支实际不发送或发送失败后无法重试的问题。

# Version 2.1.8

> Release: 2026-07-18

## 主要变化

- 启动和凭据恢复后自动恢复 WebSocket，明确手动断开则保持停用。
- 发货文本支持 AI 润色，确认后再保存；默认每 12 小时本地全量备份，可配置保留份数和目录。
- 当时统一为“豫晨咸鱼助手”品牌，并记录了标签发布流程建设。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。

# Version 2.1.7

> Release: 2026-07-18

## 主要变化

- WebSocket 连续重连失败 5 次后发送断开邮件，账号级邮件防抖保持 10 分钟。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。

# Version 2.1.6

> Release: 2026-07-18

## 主要变化

- 集中协议常量与请求指纹，修复认证刷新重试参数丢失，增加账号级失败冷却；不包含验证码绕过。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。

# Version 2.1.5

> Release: 2026-07-18

## 主要变化

- 补齐商品资料编辑、本地草稿和状态筛选，提供官方商品与发布页入口供人工确认。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。

# Version 2.1.4

> Release: 2026-07-18

## 主要变化

- 补充自动回复验证记录、QQ/Foxmail SMTP 配置说明，并统一版本元数据。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。

# Version 2.1.3

> Release: 2026-07-15

## 主要变化

- 增加健康检查、受控异步发货和人工核对队列，提供官方验证链接与快速导航，改善数据面板轮询。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。

# Version 2.1.2

> Release: 2026-07-15

## 主要变化

- 修复 Docker 复制旧 JAR 导致的发布问题，并同步版本回退值；更新相关依赖。

> 来源：原发布记录；未重新验证历史发布附件或测试结果。
