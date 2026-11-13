# 项目分析与本地升级报告

日期：2026-11-13（Asia/Shanghai）。基线：`main` / `ec06098` / 3.5.23。实际目录：`E:\移动云盘同步盘\Xianyu Assistant`；项目规则中的桌面路径不存在。没有改写规则文件、提交、推送、发布或操作真实闲鱼业务。

## 范围与工作区

启动时已有未跟踪 `.evolution/`、`PROJECT_AUDIT_REPORT.md`、`dbdata.backup-20261112-064437/` 和 `docs/升级方案-v3.6-2026-11-10.md`，全部保留。未读取备份内容或密钥值。分析基于实际源码、配置、测试和本次联网查询；旧报告仅用于辨识历史背景，不作为当前验证结果。

本轮进行了功能地图和关键边界审查，深查并改进 Webhook 的订阅→落库→排队→发送→重试→界面链路，同时检查登录/RBAC、备份、任务和发货租约入口。这不是全仓逐行安全认证，也不声称穷尽所有潜在升级。

## 项目认知与功能地图

| 领域 | 当前实现与边界 |
| --- | --- |
| 形态与技术 | 本地/自托管 Web 控制台；Java 21、Spring Boot 3.5.16、MyBatis-Plus/SQLite，Vue 3/TypeScript/Vite，Flutter 移动壳；Apache-2.0。前端现无 Pinia 依赖，不能沿用旧报告中的描述。 |
| 入口/运行 | `XianYuAssistantApplication`；Maven Wrapper 打包 JAR；Vue `npm run build` 输出到 Spring static；默认 127.0.0.1:12400；Windows 脚本、Docker 和 CI 均存在。 |
| 账号/安全 | 首账号注册为管理员、JWT 与数据库令牌校验、RBAC 注解、账号范围控制；凭据本地加密。 |
| 商品/客服 | 商品/SKU、模板与发布、消息、关键词/AI/议价/RAG、人工接管；第三方协议依赖不能由本地测试证明可用。 |
| 发货/库存 | 多段模板、卡密预占、订单租约、失败补偿；当前订单互斥为进程内模型，集群需另行设计。 |
| 自动化 | JobEngine/死信、Cron、MCP 命令、监控通知、Webhook outbox；不重复开发已有功能。 |
| 数据/运维 | SQLite schema + V3_27 及之前的增量迁移、备份、日志、健康检查和更新模块。本轮不执行恢复、升级安装或发布。 |
| 移动端 | `mobile/lib/main.dart` 与 widget 测试；复用 Web 控制台，不能称为完整原生业务实现。 |
| 未完成路径 | `ItemServiceImpl` 的浏览器商品详情兜底仍明确标记未实现；本轮未增加对易变平台接口的猜测实现。 |

## 五个开源参考项目

通过 GitHub 连接器读取目录树、README、选定核心代码、许可证和最新 Release 集合，并用联网搜索交叉定位同类项目。以下维护时间是本次 API 快照；没有下载执行或复制第三方代码，没有引入新运行时。

| 项目 | 直接阅读的核心依据 | 维护/许可证 | 采用判断 |
| --- | --- | --- | --- |
| [shaxiu/XianyuAutoAgent](https://github.com/shaxiu/XianyuAutoAgent) | [context_manager.py](https://github.com/shaxiu/XianyuAutoAgent/blob/main/context_manager.py)：SQLite 对话/商品上下文与会话索引；README 的议价与人工接管 | 最近推送 2026-06-10；GPL-3.0；Release 查询为空 | 本项目已有 AI/议价/上下文，不重复建设。仅比较思路，不移植 GPL 代码。 |
| [zhinianboke/xianyu-auto-reply](https://github.com/zhinianboke/xianyu-auto-reply) | [common/db/retry.py](https://github.com/zhinianboke/xianyu-auto-reply/blob/main/common/db/retry.py)：区分可重试连接异常与业务异常；README 的多账号、发货、发布、分段恢复 | 最近推送 2026-11-13；AGPL-3.0；Release 查询为空 | 借鉴明确的重试终止条件，修复损坏 payload 被持续重投；保留 Java/SQLite，不移植代码。 |
| [cv-cat/XianYuApis](https://github.com/cv-cat/XianYuApis) | [goofish_live.py](https://github.com/cv-cat/XianYuApis/blob/master/goofish_live.py) 与 README：WebSocket 消息接收/确认与 HTTP API 分层 | 最近推送 2026-08-18；根目录未见 LICENSE，API 无许可证；Release `goofish_v1.0.1` | 只作协议分层参考，不推断再分发许可、不复制代码；不改动已工作的消息接入。 |
| [chatwoot/chatwoot](https://github.com/chatwoot/chatwoot) | [Webhook 管理界面](https://github.com/chatwoot/chatwoot/blob/develop/app/javascript/dashboard/routes/dashboard/settings/integrations/Webhooks/Index.vue)：过滤、加载标识与管理交互；README 和 LICENSE | 最近推送 2026-11-13；最新 Release `v4.19.1`；基础目录 MIT、enterprise 另有许可 | 参考可检索、可反馈的集成管理体验，在现有 Vue 设计中接通历史筛选和加载反馈，不复制组件。 |
| [svix/svix-webhooks](https://github.com/svix/svix-webhooks) | [core/retry.rs](https://github.com/svix/svix-webhooks/blob/main/server/svix-server/src/core/retry.rs)：按错误判定和有界重试序列终止；README、队列目录与 LICENSE | 最近推送 2026-11-12；最新 Release 集合首项 `bridge-v1.102.0`；MIT | 借鉴可观察且有界的投递模型，修正次数、终态、排队保护，增加稳定请求标识；不引入 Rust 服务。 |

## 差距、优先级与实施决策

| 维度/优先级 | 当前不足 | 收益、成本与兼容 | 本轮结果 |
| --- | --- | --- | --- |
| 核心功能 P1 | 空数组 UI 声称全部事件、后端却一个也不匹配 | 修复默认配置失效，低成本；空数组行为与已有界面承诺对齐 | 已修复并测试空白、空数组、通配符、限定事件和损坏 JSON |
| 数据一致性 P0 | MyBatis 默认不写 null，成功后旧错误/重试时间留存；异步全行更新覆盖新配置 | 防止无效重投和配置回退；无 schema/API 破坏 | 已修复，真实 SQLite 往返测试覆盖 |
| 自动化 P1 | 首次排队未占用、成功次数归零、请求头恒为 1、坏载荷继续退避 | 减少重复和误导；保持现有总尝试上限语义 | 已修复首次排队占用、累计计数、终态复核和坏载荷终止 |
| 扩展/集成 P1 | 接收方无稳定投递编号；长幂等键被截断 | 新增请求头兼容旧接收端；摘要不引入依赖 | `X-Webhook-Id`、正确尝试序号；长键 SHA-256 |
| 用户体验 P1/P2 | 已有历史 API 未接页面；失败/空态不清晰；保存/测试可重复触发 | 复用 API/组件，无新依赖；状态筛选限最近100条 | 已接通历史面板、刷新和状态筛选、加载锁、窄屏和焦点提示 |
| 安全 P1 | 客户端异常可能含 URL 查询凭据 | 降低历史/日志泄露，保留异常类型 | 新异常只输出安全提示；不批量修改旧日志 |
| 架构 P3 | 多实例投递及订单锁尚非持久化租约 | 涉及部署/迁移/外部副作用，不适合盲目改造 | 保留单实例边界，列入后续 |

## 重要文件变更

- `WebhookService.java`：订阅匹配、占用保护、发送上限/编号、累计计数、终态、仅运行字段回写、安全错误。
- `WebhookDelivery.java`：4 个可空结果字段明确写 null，真正清除数据库旧值。
- `vue-code/src/api/webhooks.ts`：投递历史类型与真实请求封装。
- `vue-code/src/views/webhooks/WebhookDeliveryHistory.vue`：独立历史组件、筛选/刷新/竞态保护与响应式展示。
- `vue-code/src/views/webhooks/index.vue`：接入历史入口、反馈及重复提交防护，修正次数语义。
- `WebhookDeliveryReliabilityTest.java`：SQLite、队列占用、幂等键、坏载荷、上限与安全错误回归。
- `WebhookBrowserIT.java`：显式执行的隔离 JAR/浏览器验收；前端 `webhook-history.test.ts` 覆盖筛选、错误恢复、切换竞态。
- `.gitignore`：忽略 `dbdata.backup-*` 本地备份，防止凭据密钥和业务 JSON 被误提交；备份内容未修改。
- README、CHANGELOG、本文及 `webhook-delivery.md`：同步真实能力和验证边界。无源码删除、依赖或版本号变更。

## 验证记录

| 状态 | 命令/检查 | 本轮实际结果 |
| --- | --- | --- |
| PASS | `./mvnw.cmd -B -ntp package` | 退出 0，708 项测试、0 失败/错误、3 跳过；生成包含前端的 `target/XianYuSellerConsole-3.5.23.jar`。 |
| PASS | `-Dtest=WebhookServiceTest,WebhookDeliveryReliabilityTest test` 及后续扩展回归 | 初次专项 17 项通过；补充上限与安全错误两项后，新回归类 7 项全部通过。最终本轮覆盖的普通测试为 710 项（707 通过、3 跳过），不是重新运行全部 710 项的单次结果。 |
| PASS | `npm test` | 43 项通过，含新增 3 项历史交互测试。 |
| PASS | `npm run type-check`、`npm run lint:check`、`npm run build` | 均退出 0，生产资源已构建。未新增或升级依赖。 |
| PASS | `flutter test --no-pub` | 2 项通过。 |
| PASS（重跑） | `flutter analyze --no-pub` | 原中文路径下分析服务报 JSON 截断、退出255；按现有脚本策略临时映射空闲盘符后重跑，退出0、No issues found，映射已撤销。 |
| PASS | `git diff --check`、`git check-ignore` | 无空白错误；备份密钥和业务 JSON 被忽略。 |
| PASS | `-Dtest=WebhookBrowserIT test` | 1 项隔离浏览器集成测试通过：JAR 健康检查、匿名历史访问被拒绝、注册/登录、端点创建/禁用/重载持久化、历史空态/刷新/筛选/载荷脱敏；1440×1000 和390×844，两种宽度；无 JS 页面错误、窄屏无水平溢出。 |
| PARTIAL | 安装/外部端到端 | 复用现有 Maven/npm/Flutter 缓存完成构建；没有重装依赖、真实账号消息/发货、真实公网 Webhook、Android/iOS 安装、长稳压测或更新安装验证。 |

普通测试的 3 个既有跳过原因：Windows 不能替换含打开文件的目录、不能创建符号链接、`selfupdate.e2e` 属性未启用。均未标为通过。

浏览器工具环境记录：内置 Browser 运行组件缺失；改用项目已有 Java Playwright。第一次执行触发自动安装浏览器而未完成，已停止该测试的下载子进程，并加入显式跳过下载选项复用本地 Chromium；参见 [Playwright 官方说明](https://playwright.dev/java/docs/browsers#skip-browser-downloads)。此失败属于测试环境准备，不掩盖为通过。

第二次浏览器执行因脚本对隐藏 input 使用 `uncheck()` 超时；改为点击实际可见开关后完整测试通过。截图复核发现缩窄视口后立即截取会捕获侧栏退场动画，因此增加等待通知消失和侧栏退出视口的可观察条件后重新截图；没有禁用或篡改产品动画。

运行截图为真实页面，记录来自隔离测试库，端点明确标为“隔离验收（测试记录）”，不能当作真实闲鱼交易/公网回调证据：

- [桌面截图](images/webhook-history-desktop-2026-11-13.png)
- [手机宽度截图](images/webhook-history-mobile-2026-11-13.png)

原始本地证据位于 `target/full-verification.log`、`webhook-tests.log`、`frontend-{tests,lint,build}.log`、`mobile-{tests,analyze,analyze-ascii}.log`、`browser-verification*.log` 和 `surefire-reports/`。这些缓存不纳入源码提交。

## 完成度与后续

本轮以修复可复现缺陷并交付可测试增量为验收，不为整个产品给无依据的高分。

| 维度 | 当前结论与依据 |
| --- | --- |
| 功能完整度 | 本轮 Webhook 链路补齐；真实闲鱼登录/消息/发货仍需账号环境验收。 |
| UI/UX | 历史筛选、错误恢复和窄屏已实现；全站视觉重构未进行。 |
| 代码质量 | 单独组件与行为测试降低耦合；其余巨型业务类仍需分批审查。 |
| 性能 | 队列有界、查询 limit 有界、历史按需加载；无长期压测证据。 |
| 安全性 | 本轮保留认证/SSRF/脱敏，修复异常回显；不是完整渗透测试。 |
| 稳定性 | SQLite 终态与累计次数已验证；多进程及崩溃后外部副作用不能保证恰好一次。 |
| 可维护性 | 无新依赖/迁移，新增操作文档与可重复验收。 |
| 扩展性 | 新请求头便于接收端去重；分布式租约及完整回放机制仍是后续工作。 |

后续优先级：P1 为真实平台端到端验收、接收端幂等约定、禁用端点积压的扫描公平性和历史记录分页；P2 为全站交互回归与长稳/压力基线、按业务拆分大文件；P3 为明确多实例需求后的持久化租约。手工重投需先定义业务副作用与审计，不直接堆按钮。
