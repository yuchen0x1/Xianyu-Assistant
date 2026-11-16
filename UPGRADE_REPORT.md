# 项目自主进化升级报告

报告日期：2026-11-16（Asia/Shanghai）
项目：闲鱼卖家运营控制台
基线：`main` / `e141e18` / `v3.5.25`
交付版本：`v3.5.26`（本轮发布）
工作区：`E:\移动云盘同步盘\Xianyu Assistant`

## A. 竞品研究

本轮研究了 **18 个**相关开源项目，分为电商后台、客服/运营控制台、AI/RAG、Webhook/任务编排、监控和自托管后台几类。重点项目和证据入口见 [COMPETITOR_ANALYSIS.md](COMPETITOR_ANALYSIS.md)。

最值得借鉴的设计是：

- Saleor、Medusa、Vendure 的模块、Provider、Plugin Contract 和稳定领域 API。
- Chatwoot 的会话/集成管理反馈、筛选、空态和加载状态。
- Dify、Open WebUI 的 RAG、工具调用、人工确认和 AI 错误可观测性。
- Temporal、Airflow、Kestra 的 lease、执行历史、重试、回填和可恢复流程边界。
- Node-RED、n8n 的工具/节点/流程契约和人工审批挂起模式。
- Metabase、Appsmith 的指标语义、可配置面板和安全查询边界。

本项目当前领先或保持独特的部分：

1. 将账号、商品、客服、发货、卡密、任务、Webhook、监控、备份和在线更新放在一个本地优先控制台内。
2. 对凭据加密、日志脱敏、AI 端点 SSRF、Webhook 出站和发布物签名有服务端边界。
3. 使用 SQLite、Maven/Vite、Docker 和 GitHub Actions，部署门槛低且无需本项目提供第三方业务服务器。
4. 已有账号范围 RBAC、死信、告警状态机、备份和更新恢复，而多数协议脚本只覆盖其中一个业务环节。

主要短板：真实平台兼容性和长稳证据不足；浏览器商品详情兜底尚未实现；Token 仍在 localStorage；历史接口分页、多实例租约、CSP、SBOM、恢复演练和无障碍自动门禁仍在路线图中。

## B. 升级数量与完成状态

路线图共 **45 个**独立升级点：

| 状态 | 数量 | 说明 |
| --- | ---: | --- |
| 本轮完成 | 3 | EV-001、EV-002、EV-003，均有源码和回归测试 |
| 已完成（基线已有） | 27 | EV-004 至 EV-030，来自当前代码、测试和历史技术档案；不冒充本轮新增 |
| 部分完成 | 1 | EV-041，已有隔离浏览器验收基础，但不覆盖真实平台 |
| 待实施 | 14 | EV-031 至 EV-040、EV-042 至 EV-045，需要进一步设计、外部部署条件或迁移评估 |

### 本轮完成项

- **EV-001**：成功登录/会话刷新复位前端过期跳转闸，避免重新登录后后续过期不跳转。
- **EV-002**：权限请求绑定 Token，账号切换时不复用旧 in-flight 请求，也不让旧响应写入新缓存。
- **EV-003**：WebSocket 协议 UUID 改为线程本地随机源和原子单调时间戳，避免同毫秒并发碰撞。

### 未完成项与原因

- **EV-031**：浏览器商品详情兜底需要适配易变的真实平台页面和凭据环境，当前没有安全的稳定字段契约。
- **EV-032**：分页需要同时调整后端响应契约、Mapper 查询和前端列表，先保留固定上限行为避免无计划的 API 破坏。
- **EV-033**：多实例租约涉及节点身份、迁移、部署拓扑和故障演练；项目当前默认单机 SQLite，不能只加一个锁就宣称完成。
- **EV-034/035**：HttpOnly 会话与 CSP 是跨层安全迁移，需要先验证反代、WebSocket、上传、内联样式和 CSRF 兼容性。
- **EV-036/037/038**：结构化追踪、性能基线和 SQLite 维护需要长期运行数据，单次本地构建不能提供真实指标。
- **EV-039**：恢复 dry-run 需要隔离临时库、版本清单和人工确认流程，避免把测试恢复写入用户数据库。
- **EV-040**：SBOM 需要确定发布工件保留位置和许可证审阅流程，不能只生成一个未被消费的文件。
- **EV-042/043/044/045**：分别需要 E2E 可访问性环境、配置迁移契约、命令生态版本设计和产品级国际化范围，列入后续批次。

## C. 代码与文档修改

### 源码与测试

- 修改 `vue-code/src/utils/request.ts`。
- 修改 `vue-code/src/composables/usePermissions.ts`。
- 新增 `vue-code/tests/permissions-races.test.ts`。
- 修改 `src/main/java/com/feijimiao/xianyuassistant/utils/XianyuDeviceUtils.java`。
- 新增 `src/test/java/com/feijimiao/xianyuassistant/utils/XianyuDeviceUtilsTest.java`。

### 文档

- 新增 `PROJECT_AUDIT.md`：项目定位、架构、能力、风险、基线和成熟度。
- 新增 `COMPETITOR_ANALYSIS.md`：18 个项目的证据入口、许可证边界、对比矩阵和采用判断。
- 新增 `UPGRADE_ROADMAP.md`：EV-001 至 EV-045 的问题、依据、方案、文件、优先级和验收标准。
- 新增本报告 `UPGRADE_REPORT.md`。
- 更新 `README.md`：加入审计/竞品/路线图/报告入口和本轮变更摘要。
- 更新 `CHANGELOG.md`、README 与发布记录：将本轮完成项整理为 `v3.5.26` 发布条目，并保留 `v3.5.25` 基线说明。

没有删除文件，没有数据库迁移，没有新增运行时依赖，没有覆盖 `dbdata/` 或任何用户配置。

## D. 测试与质量验收

| 状态 | 检查 | 结果 |
| --- | --- | --- |
| PASS | `cd vue-code; npm test -- --run` | 9 个文件、43 个测试通过（基线） |
| PASS | `cd vue-code; npm test -- --maxWorkers=1 tests/request.test.ts tests/permissions-races.test.ts` | 2 个文件、9 个聚焦测试通过 |
| PASS | `cd vue-code; npm run type-check` | 通过 |
| PASS | `cd vue-code; npm run lint:check` | 通过 |
| PASS | `cd vue-code; npm run build` | 通过，Vite 产出 Spring static |
| PASS | `mvnw.cmd -Dtest=com.feijimiao.xianyuassistant.utils.XianyuDeviceUtilsTest test` | 1 个聚焦测试通过 |
| PASS | `mvnw.cmd test -DskipITs` | 最终复跑 711 个测试，0 失败、0 错误、3 跳过；基线为 710 个 |
| PASS | `git diff --check` | 通过 |

后端 3 个跳过项属于既有环境/条件分支，未被当作端到端通过。前端测试中的 jsdom 导航提示是既有测试环境提示，命令退出码为 0。npm 的配置警告和 Java 编译警告没有被隐藏或改成成功结果。

### 尚未完成的验证

- 没有真实闲鱼账号、真实订单/消息或真实公网 Webhook 接收端，因此不能声称平台端到端通过。
- 没有完成旧版本客户端的真实安装、自更新、进程管理和失败回滚演练。
- 没有在多实例部署下验证跨进程租约、恰好一次投递或高并发性能。
- 没有执行未经授权的真实数据恢复或生产部署。

## E. 项目变化

### 升级前

v3.5.25 已经是一个功能较完整的本地 Web 卖家控制台，具备账号、商品、消息、发货、任务、Webhook、监控、备份、RBAC、凭据加密和发布签名等能力；本轮基线测试显示前后端构建和回归测试均可通过。仍存在跨会话竞态风险、并发协议标识碰撞风险，以及大量需要外部环境证明的产品能力。

### 升级后

本轮修复了两个前端会话/权限边界缺陷和一个后端并发标识缺陷，并以回归测试固定行为。项目同时有了可复查的现状审计、18 项目竞品对标、45 项升级路线图和逐项完成状态报告。现有功能没有删除，数据库和依赖没有变化。

### 下一阶段最值得做的事情

1. 在隔离环境完成商品详情浏览器兜底的接口契约和浏览器 E2E，再决定是否进入生产路径。
2. 明确单机部署与多实例部署支持边界，完成备份恢复演练后再做持久化租约。
3. 设计 HttpOnly Cookie + CSP 迁移方案，先 report-only，再用真实反代和 WebSocket 回归验证。
4. 为 Webhook、消息、操作日志增加分页和 request/trace ID，并建立长稳、内存、CPU、磁盘和恢复基线。

本报告不把路线图中的待办、模拟数据、代码存在或一次构建成功表述为生产级端到端完成。
