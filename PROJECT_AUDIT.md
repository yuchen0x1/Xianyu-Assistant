# 项目现状审计

审计日期：2026-11-16（Asia/Shanghai）
仓库：`E:\移动云盘同步盘\Xianyu Assistant`
分支：`main`
基线提交：`e141e18`（`v3.5.25`，与 `origin/main` 一致）

## 1. 审计范围与证据

本审计基于实际工作区、源代码、配置、迁移脚本、前后端测试、CI 工作流和已有发布档案。没有把 README 的功能列表直接当作实现证据；每一项能力都尽量追踪到入口、服务、持久化和测试。未读取或输出 `dbdata/` 中的业务数据、密钥或 Cookie。

本轮只在本地工作区实施变更，没有推送、发布、部署或操作真实闲鱼账号。

## 2. 产品定位

项目是面向自有闲鱼账号的本地/自托管卖家运营 Web 控制台。后端以 Java 21 + Spring Boot 3.5 单体服务运行，Vue 3/TypeScript 前端由 Vite 构建到 Spring Boot 的静态资源目录，默认使用本地 SQLite 保存业务数据。核心场景包括多账号连接、商品资料与发布、消息/自动回复、订单与自动发货、卡密库存、定时任务、Webhook、备份、监控和在线更新。

当前产品边界是“浏览器访问同一个 Web 服务”：JAR 与 Docker 是部署形态，不是独立桌面客户端；仓库不提供原生 Android/iOS 业务应用。

## 3. 技术架构

| 层次 | 实际实现 |
| --- | --- |
| 运行入口 | `src/main/java/com/feijimiao/xianyuassistant/XianYuAssistantApplication.java` |
| 后端 | Spring MVC、Validation、AOP、Actuator、Micrometer、MyBatis-Plus、SQLite |
| 外部协议 | OkHttp、Java-WebSocket、Playwright、ZXing、Gson、MessagePack |
| AI | Spring AI OpenAI 兼容客户端、动态配置、SimpleVectorStore |
| 前端 | Vue 3.5、Vue Router、TypeScript、Vite、Vitest、Axios |
| 持久化 | `src/main/resources/sql/schema.sql` + `db/migration/V3_*` 增量迁移 |
| 认证授权 | JWT、会话轮换、登录限流、RBAC、账号范围控制 |
| 交付 | Maven Wrapper、Dockerfile、Windows 脚本、GitHub Actions CI/Release、带 SHA-256/Ed25519 校验的安装/自更新流程 |

## 4. 功能模块清单

### 已有并能从代码/测试确认的能力

- 账号注册、登录、JWT 会话轮换、RBAC 和账号范围隔离。
- QR/浏览器/手动凭据接入、Cookie 加密、WebSocket 连接、心跳、重连、健康巡检。
- 商品同步、商品资料、SKU、价格历史、发布模板、批量导入与发布。
- 消息会话、历史消息、人工接管、关键词回复、AI 回复、知识库上下文和智能议价。
- 订单查询、发货模板、文本/图片/卡密发货、库存预占/回滚、失败重试和 API 兜底开关。
- 定时任务、JobEngine 幂等租约、失败退避、死信队列、补偿和自动评价工作流。
- Webhook 订阅、HMAC 签名、SSRF 防护、持久化投递历史、状态筛选与重试。
- 监控概览、告警去重/抑制/确认/恢复、多渠道通知、Prometheus 可选暴露。
- SQLite 全量备份/恢复、Excel 导出、日志审计、在线更新检查与自更新。
- MCP JSON-RPC 端点和内置命令工具化调用。

### 只有外部环境才能完全证明的能力

- 真实闲鱼账号登录、Cookie 长期保活、WebSocket 协议兼容性和风控结果。
- 真实订单/消息/发货副作用、真实公网 Webhook 接收方和多平台网络条件。
- JAR 自更新的旧客户端发现、验签、替换、重启和回滚全链路。
- 多实例部署下的跨进程恰好一次投递与订单锁；当前默认边界是单机 SQLite。

## 5. 当前优势

1. 功能覆盖已从单一自动发货扩展到账号、商品、客服、履约、库存、任务、Webhook 和运维闭环。
2. 多个高风险边界已经有服务端防线：凭据加密、日志脱敏、RBAC、登录限流、AI 端点 SSRF 校验、Webhook 出站校验和发布签名。
3. 数据库迁移、备份、死信、告警和更新流程都有持久化或状态机设计，不依赖“页面上有一个按钮”来假装完成。
4. CI 同时检查后端测试、前端测试、Lint、类型、构建、依赖审计和 Docker 构建。
5. 现有测试规模较大，适合继续用小批量可回归改动推进，而不需要立即重写架构。

## 6. 发现的问题与技术债务

| 编号 | 问题 | 证据/影响 | 优先级 |
| --- | --- | --- | --- |
| A-01 | 平台协议和商品详情接口易变 | `ItemServiceImpl` 仍把浏览器详情兜底标记为未实现；外部账号环境无法由本地单测证明 | P1 |
| A-02 | 浏览器端 Token 保存在 `localStorage` | `vue-code/src/utils/request.ts`；一旦未来引入 XSS/第三方脚本会扩大会话窃取面 | P1 |
| A-03 | SQLite 与部分租约模型默认单实例 | JobEngine/发货保护有持久化与进程内边界，多实例仍需明确部署约束 | P1 |
| A-04 | 长列表和历史接口仍有进一步分页空间 | 当前部分历史页面按最近固定数量加载，数据规模增大后查询和浏览体验会下降 | P2 |
| A-05 | 大型业务类耦合较高 | `ItemServiceImpl`、`XianyuWebSocketClient`、部分服务实现同时承担协议、重试和编排 | P2 |
| A-06 | 缺少长期运行/压力基线 | 本轮有单测和构建证据，没有真实账号长稳、内存、CPU 和公网回调基线 | P2 |
| A-07 | 真实浏览器 E2E 受环境约束 | 本地 CI/Playwright 可验证页面流，但不能替代真实闲鱼和公网接收端 | P1 |
| A-08 | 依赖与供应链治理仍需持续维护 | 当前 CI 有 npm audit 和固定 Action SHA，但缺少统一 SBOM/许可证快照 | P3 |
| A-09 | 全站可访问性尚未形成自动门禁 | 代码已有语义按钮、焦点和 reduced-motion 处理，仍缺少完整键盘/读屏回归 | P2 |
| A-10 | 更新/恢复演练依赖部署环境 | 签名代码和测试存在，真实旧版本安装升级、失败回滚和外部进程管理未在本机完整证明 | P1 |

## 7. 本轮基线验证

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 前端单测 | `cd vue-code; npm test -- --run` | PASS：9 个文件、43 个测试 |
| 前端类型 | `cd vue-code; npm run type-check` | PASS |
| 前端 Lint | `cd vue-code; npm run lint:check` | PASS |
| 前端构建 | `cd vue-code; npm run build` | PASS：384 个模块，输出到 Spring static |
| 后端测试 | `mvnw.cmd test -DskipITs` | PASS：710 个测试，0 失败、0 错误、3 跳过 |
| 工作区 | `git status --short --branch` | 基线检查时干净；本轮修复后有明确的源码和测试变更 |

测试中的 jsdom 导航提示、npm 配置警告和 Java 编译警告没有被当作失败；3 个后端跳过项属于既有环境/条件限制，不能当作端到端通过。

## 8. 本轮已实施的 P0/P1 修复

- `vue-code/src/utils/request.ts`：成功登录或会话刷新时重置过期跳转闸，避免同一 SPA 运行周期内重新登录后不再跳转。
- `vue-code/src/composables/usePermissions.ts`：将权限请求与 Token 绑定，防止旧账号慢响应覆盖新账号权限缓存。
- `vue-code/tests/permissions-races.test.ts`：增加旧 Token 慢响应回归测试。
- `src/main/java/com/feijimiao/xianyuassistant/utils/XianyuDeviceUtils.java`：使用 `ThreadLocalRandom`，并让并发生成的协议 UUID 在同一毫秒内保持唯一单调。
- `src/test/java/com/feijimiao/xianyuassistant/utils/XianyuDeviceUtilsTest.java`：增加 2,000 次并发生成唯一性测试。

## 9. 成熟度判断

项目已经具备可交付的本地 Web 控制台工程基础：功能面广、自动化和安全边界比早期版本完整，且本地构建和回归测试稳定。当前不能仅凭本地测试宣布“生产级全场景已验证”，因为平台协议、真实账号副作用、公网 Webhook、多实例和升级安装仍需要外部环境证据。

下一步优先级应是：真实平台兼容性与长稳验收、历史接口分页、浏览器 Token 边界、部署恢复演练、以及在明确多实例需求后再设计持久化租约。路线图见 [UPGRADE_ROADMAP.md](UPGRADE_ROADMAP.md)，竞品证据见 [COMPETITOR_ANALYSIS.md](COMPETITOR_ANALYSIS.md)。
