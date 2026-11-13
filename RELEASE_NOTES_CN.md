# 中文发布说明

> 当前产品范围（2026-11-13）：仅维护网页版，Android/iOS 工程与独立桌面启动包装已移除。下文的原生移动端功能和检查结果仅为旧版本历史记录，不代表当前提供独立客户端。电脑和手机均通过浏览器访问。

v3.5.16–v3.5.24 的逐版说明。更早版本见[完整历史](docs/releases/release-notes.md)。日期使用北京时间；历史验证记录不代表本次重测。

## v3.5.24

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

## v3.5.23

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

## v3.5.22

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

## v3.5.21

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

## v3.5.20

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

## v3.5.19

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

## v3.5.18

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

## v3.5.17

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

## v3.5.16

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
