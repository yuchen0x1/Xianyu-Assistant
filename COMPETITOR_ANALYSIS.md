# COMPETITOR_ANALYSIS.md

> 研究日期：2026-11-16（Asia/Shanghai）
> 研究对象：本仓库的「闲鱼卖家运营控制台」（v3.5.25，Java 21/Spring Boot + Vue 3/TypeScript，SQLite 本地部署）。
> 研究范围：18 个公开源代码项目，覆盖电商后台、订单/库存、客服与消息、工作流编排、AI 应用、数据分析和低代码后台。它们不是全部的直接竞品；它们代表可以迁移到本项目的产品能力和工程方案。

## 1. 研究方法与证据边界

本次研究使用 GitHub 官方仓库页面、仓库 README、许可证文件、目录结构和 GitHub 提交记录。每个项目至少检查了 README 和 LICENSE（部分项目的许可证文件名称不是根目录 LICENSE，此时以 README 的许可证说明为准），并抽查了核心目录/包结构。活跃度以 2026-08-18 至 2026-11-16 的提交记录为参考，不把仓库宣传文字当成已经验证的运行能力。

本报告中的“适合借鉴”表示设计方向或重新实现思路，不表示可以直接复制代码。引入代码前仍需逐文件确认许可证、版权声明、依赖许可证和安全边界。

本项目基线来自当前仓库实际文件：根目录 README.md、pom.xml、vue-code/package.json、src/main/java、src/main/resources/sql、vue-code/src/api、vue-code/src/views、vue-code/src/utils/request.ts、.github/workflows 和现有测试目录。当前项目的优势是面向闲鱼的账号/商品/消息/发货/卡密/自动化闭环、本地 SQLite、WebSocket 连接健康、AI 回复与人工确认、Webhook、监控和带签名的更新脚本；当前差距集中在通用渠道抽象、可视化工作流、消息协作、可扩展插件生态、长期任务编排、数据分析层和更广的集成生态。

## 2. 重点项目清单

| # | 项目 / 领域 | 许可证与活跃度证据 | README / 源码中核实的能力 | 可迁移价值 | 我们当前相对优势 / 差距 |
|---:|---|---|---|---|---|
| 1 | [Saleor](https://github.com/saleor/saleor) / headless commerce | BSD-3-Clause；[许可证](https://github.com/saleor/saleor/blob/main/LICENSE)；[2026-11-13 提交](https://github.com/saleor/saleor/commit/5e1679b1e91f76ac9fd553a62adeffb6e9acf5c3) | README 明确为 GraphQL-only、API-only、headless、原生多渠道；包含商品、订单、库存、促销、支付、Webhook、Apps；核心在 saleor/，后台为独立的 [saleor-dashboard](https://github.com/saleor/saleor-dashboard)。 | 把平台账号/商品/订单能力抽象为稳定资源 API；用 Webhook 和外部 App 解耦扩展；按渠道隔离价格、库存和可见性；为 MCP/AI 工具提供清晰领域边界。 | 我们在闲鱼 WebSocket、自动发货和卡密风控上更贴近目标用户；我们缺少 GraphQL/插件边界、统一渠道模型和可独立部署的扩展层。 |
| 2 | [Medusa](https://github.com/medusajs/medusa) / modular commerce | MIT 核心，Enterprise 目录另有商业许可；[LICENSE](https://github.com/medusajs/medusa/blob/develop/LICENSE)；[2026-11-13 提交](https://github.com/medusajs/medusa/commit/01de0be27a7c228795d5cfc31662ca144781fbfc) | README 列出 packages/core、packages/modules、Provider、Admin、Design System、CLI 和 integration-tests；模块覆盖商品、购物车、订单、定价、支付、事件总线、缓存、锁和工作流；近期提交修复了管理员公共认证配置和 UI 可访问性。 | 参考模块/Provider/Workflow 分层；把闲鱼 API、发货、通知、代理、AI 服务做成可替换 Provider；把集成测试与模块测试分开。 | 我们已有较完整业务模块，但服务层与平台适配耦合更紧；缺少统一模块 SDK、事件总线和可复用工作流包。 |
| 3 | [Vendure](https://github.com/vendure-ecommerce/vendure) / GraphQL + NestJS commerce | GPLv3；[LICENSE.md](https://github.com/vendurehq/vendure/blob/master/LICENSE.md)；[v3.8.2 发布提交](https://github.com/vendurehq/vendure/commit/d449b19cfee6f2c173dcb15164e9ab2d55f34707) | README 明确使用 GraphQL、NestJS、React；通过 Plugin Contracts、Service Overrides、自定义实体、Pricing/Workflow 扩展服务 B2B、D2C、marketplace 和 omnichannel；内置目录、订单、促销、渠道、税、物流、支付和库存。 | 设计受约束插件 SPI；为账号、连接器、自动回复策略和发货策略定义扩展契约；在 API 层暴露可 introspect 的 schema，方便 AI/MCP 工具调用。 | 我们的闲鱼专用流程更深入；缺少通用插件契约、渠道/价格/税等可组合域模型。GPLv3 代码不能直接并入 Apache-2.0 项目，需重新实现或单独进程隔离。 |
| 4 | [Sylius](https://github.com/Sylius/Sylius) / Symfony commerce components | MIT；[LICENSE](https://github.com/Sylius/Sylius/blob/2.3/LICENSE)；[2026-11-16 提交](https://github.com/Sylius/Sylius/commit/26b5d131aedfc578c03da97e04af11267ad9bcf9) | 组件化的 PHP/Symfony 电商内核，强调可替换的订单、价格、库存、支付和促销组件；仓库有持续的自动化重构和开发者体验提交。 | 借鉴小而稳定的 domain component 和 state machine；将订单、发货、库存预占/回滚等流程改成明确状态机，而不是在 Controller 中拼状态。 | 我们已有发货状态和失败重试，但跨模块状态约束与可视化状态历史仍可加强。 |
| 5 | [Bagisto](https://github.com/bagisto/bagisto) / Laravel commerce | MIT；[LICENSE](https://github.com/bagisto/bagisto/blob/master/LICENSE)；[2026-11-16 依赖更新提交](https://github.com/bagisto/bagisto/commit/38ed193bd1160a89a98de2c0df3529732b7d9ec3) | Laravel 电商后台，目录、属性、购物车、结算、订单、库存、客户和后台管理由 packages/Webkul 组织；近期持续跟进 Laravel 安全/依赖升级。 | 参考按包组织的功能边界、后台 CRUD 复用、属性/SKU 建模和版本升级策略。 | 我们对卡密、闲鱼账号和 WebSocket 的支持更有特色；商品编辑和批量导入可以吸收更清晰的领域对象/校验层。 |
| 6 | [ERPNext](https://github.com/frappe/erpnext) / ERP + inventory | GPLv3；[许可证说明](https://github.com/frappe/erpnext/blob/develop/license.txt)；[2026-11-16 库存权限修复](https://github.com/frappe/erpnext/commit/4b112a5f8dfa9b248959835b3d3e7f5773ae668a) | 覆盖销售、采购、库存、仓库、序列号/批次、权限、会计和报表；近期提交修复序列号/批次选择器的仓库权限，说明其权限与库存联动较细。 | 借鉴库存流水、批次/序列号、仓库权限、可追溯操作记录和报表维度；把卡密库存按批次/状态/分配人/使用记录做成明确账本。 | 我们的卡密预占、回滚和闲鱼发货更聚焦；ERP 范围远大于本项目，不适合直接迁移。GPLv3 代码不可直接并入本项目。 |
| 7 | [Odoo](https://github.com/odoo/odoo) / modular business suite | LGPLv3；[LICENSE](https://github.com/odoo/odoo/blob/19.0/LICENSE)；[2026-11-16 HTML 规范修复](https://github.com/odoo/odoo/commit/d55f4ce97d443fb44af2f4cfcaea29e03a1b39a5) | addons/ 模块化；销售、CRM、库存、POS、邮件、自动化和权限组合成业务应用；持续处理边界输入和回归问题。 | 参考模块 manifest、权限声明、业务记录规则、活动/提醒和统一搜索；为操作日志、待办、评价、Webhook 失败建立一致活动模型。 | 我们已有本地化卖家闭环和更轻部署；缺少统一模块元数据、细粒度记录规则和跨模块活动中心。 |
| 8 | [WooCommerce](https://github.com/woocommerce/woocommerce) / WordPress commerce | GPLv3+；[仓库](https://github.com/woocommerce/woocommerce)；[2026-11-16 兼容性提交](https://github.com/woocommerce/woocommerce/commit/bacf4e164b20f7f5f1a115467575cc3d6ebc802e) | 提供 REST/Store API、订单/商品/客户、HPOS、扩展点和大量插件；近期提交持续关注兼容性、性能审查规则和版本发布。 | 参考公开 API 版本化、扩展 Hook、批处理和兼容性清单；将闲鱼控制台 API 按版本和权限公开，而不是让前端直接依赖内部 DTO。 | 我们在本地部署、闲鱼协议和账号风控上更有针对性；生态、第三方集成和 API 兼容性治理明显不足。 |
| 9 | [Chatwoot](https://github.com/chatwoot/chatwoot) / omnichannel support | MIT（enterprise/ 单独许可，第三方依赖按原许可证）；[LICENSE](https://github.com/chatwoot/chatwoot/blob/develop/LICENSE)；[2026-11-16 提交](https://github.com/chatwoot/chatwoot/commit/aed04e65f5c088d42c2b85304293e47367b6fe96) | README 列出统一收件箱、网站/邮件/社交/WhatsApp/Telegram 等渠道、私密备注、@ 提及、标签、快捷键、自动分配、团队/自动化、客户分群、报表和 Captain AI。 | 参考会话工作台：未读/待处理队列、内部备注、快捷回复、自动分配、SLA/业务时间、标签/分群、会话级审计；将人工接管做成一等状态。 | 我们已有闲鱼会话、AI 回复和人工确认，但缺少团队协作、分配规则、统一会话筛选和可操作 SLA/队列视图。 |
| 10 | [Dify](https://github.com/langgenius/dify) / AI workflow + RAG | 修改版 Apache-2.0，附加多租户和品牌限制；[LICENSE](https://github.com/langgenius/dify/blob/main/LICENSE)；[2026-11-16 提交](https://github.com/langgenius/dify/commit/3f72db6c4be7d3136ce69ada81e688820190bc8f) | README 列出 AI Workflow、RAG、Agent、模型管理、可观测性；api/、web/、worker/、docker/ 分层；近期修复知识库失败阶段提示和前端 dialog 生命周期。 | 参考 AI 回复工作流、知识库失败阶段、模型 Provider 抽象、人工确认节点和可观测性；为 AI 回复记录 prompt/version/引用/耗时/失败原因。 | 我们已有 Spring AI、RAG 和规则降级，但缺少可视化编排、可追踪知识库管线和统一模型/Provider 配置。许可证有额外条件，不能直接当 Apache-2.0 代码使用。 |
| 11 | [n8n](https://github.com/n8n-io/n8n) / visual automation | Sustainable Use License + Enterprise License（不是标准 OSI 开源许可证）；[README license](https://github.com/n8n-io/n8n#license)；[2026-11-16 提交](https://github.com/n8n-io/n8n/commit/fde8b89398d535916c86082da7efcce67807f8fc) | 视觉画布、AI Agent/Workflow、人工审批、可观测性、JavaScript/Python 扩展、1500+ integrations、凭据和模板；packages/editor-ui、packages/workflow、packages/core 与节点包分开。 | 参考可视化任务图、节点输入输出契约、凭据引用、执行记录、人工审批和模板市场；先在本项目内实现受限任务 DAG/节点模型，不直接复制 n8n。 | 我们已有 Cron/重试/死信和命令中心，但没有可视化 DAG、节点插件和执行详情；许可证限制使其只能作为设计参考。 |
| 12 | [Node-RED](https://github.com/node-red/node-red) / event-driven low-code | Apache-2.0；[LICENSE](https://github.com/node-red/node-red/blob/master/LICENSE)；[2026-11-13 安全修复](https://github.com/node-red/node-red/commit/50b23f8ac112b45c3c21eae352d2ba2fa15ec43f) | README 定义为 event-driven low-code；运行在 Node.js，使用可安装 nodes、flows、library；有独立编辑器和运行时，支持自定义节点。 | 参考事件 + 节点 + flow 的低门槛自动化，以及节点级输入校验、路径校验、导入/导出；可把本项目命令中心扩展成有限流程编辑器。 | 我们的任务语义更贴近闲鱼订单/消息，且后端是 Java；不应引入 Node-RED 运行时，只实现领域节点和安全导入格式。 |
| 13 | [Temporal](https://github.com/temporalio/temporal) / durable workflow | MIT；[LICENSE](https://github.com/temporalio/temporal/blob/main/LICENSE)；[2026-11-14 提交](https://github.com/temporalio/temporal/commit/170fb83dca48fe82d69aa3ec3b6fe25acfc1791b) | Durable Execution；Workflow 自动处理间歇性失败和重试；有 Server、CLI、Web UI、SDK、测试工具和架构文档。 | 参考持久化执行状态、活动重试/超时/取消、幂等和可重放历史；为发货、评价、Webhook 投递和更新下载定义稳定 job id 与状态。 | 我们已有幂等、失败重试、退避、死信/补偿，但状态仍由业务表和调度器维护；可逐步强化执行历史与恢复协议。 |
| 14 | [Apache Airflow](https://github.com/apache/airflow) / schedule + monitor | Apache-2.0；[LICENSE](https://github.com/apache/airflow/blob/main/LICENSE)；[2026-11-16 UI 性能提交](https://github.com/apache/airflow/commit/e74a7354920fcd0dee085edfb88badf2c8483622) | 以代码定义 DAG，Scheduler/Workers 执行依赖，UI 可视化、监控和故障排查；README 与 CI 显示多架构测试与 SDK 测试。 | 参考 DAG 可视化、任务依赖、回填、重跑、日志和运行时指标；把复杂批量上架/擦亮/库存同步拆成可观测任务。 | 我们已有 Cron 和任务监控，更适合轻量本地执行；缺少任务依赖图、回填和按运行实例查询。 |
| 15 | [Kestra](https://github.com/kestra-io/kestra) / event-driven orchestration | Apache-2.0；[LICENSE](https://github.com/kestra-io/kestra/blob/develop/LICENSE)；[2026-11-16 dashboard 提交](https://github.com/kestra-io/kestra/commit/e7e5b8a23b599c0cba6978332092144a30c67be8) | 事件/定时统一、YAML/No-Code/AI Copilot 同一流程模型、Git 版本控制、插件、重试、超时、错误处理、并发、配额、回填、MCP Server。 | 参考声明式任务定义、草稿/发布版本、流程输入输出、执行保留策略、配额和数据源失效提示。 | 我们有 API/MCP、Cron、重试和告警，但没有声明式流程版本和可视化编辑器；Kestra 的分布式规模超出本地 SQLite 定位。 |
| 16 | [Appsmith](https://github.com/appsmithorg/appsmith) / low-code admin | Apache-2.0；[LICENSE](https://github.com/appsmithorg/appsmith/blob/release/LICENSE)；[2026-11-16 MCP SQL 提交](https://github.com/appsmithorg/appsmith/commit/4fc8bc630bba477b28ea99f30d7a1ef3a1351766) | 面向 dashboard/admin panel/customer 360/IT automation；Docker/Kubernetes/AWS 部署；组件、数据源、权限和 MCP 结构化 SQL 查询。 | 参考配置化数据面板、查询权限、组件级错误状态和低代码 + MCP 边界；把运营指标/任务视图做成可配置小组件。 | 我们已有 Vue 页面和专用数据 API，性能与领域一致性更可控；缺少通用数据源绑定、用户自定义面板和安全查询 DSL。 |
| 17 | [Metabase](https://github.com/metabase/metabase) / analytics | AGPL（开源版；商业版另有商业许可）；[README license](https://github.com/metabase/metabase#license)；[2026-11-16 提交](https://github.com/metabase/metabase/commit/31564417f6c792c1a8519f68b272cb6bf82a0578) | 非 SQL 用户可提问、SQL 编辑器、交互式 Dashboard、告警、订阅、Webhook、权限、嵌入和语义模型；支持本地部署。 | 参考指标定义、告警/订阅、仪表盘过滤、查询缓存、嵌入和权限隔离；为商品转化、发货成功率、响应时间建立可解释指标。 | 我们已有 dashboard、Prometheus 和通知，但缺少自助分析、指标语义层和定时订阅。AGPL 代码不适合直接嵌入。 |
| 18 | [Open WebUI](https://github.com/open-webui/open-webui) / self-hosted AI UI | Open WebUI License（BSD 风格附加条款，需逐项审查）；[LICENSE](https://github.com/open-webui/open-webui/blob/main/LICENSE)；[2026-11-08 工具调用修复](https://github.com/open-webui/open-webui/commit/227287b3b52a0d7fd3dfd97dd7a1e15f7b661a9d) | 可离线运行、自托管、连接 Ollama/OpenAI-compatible API；知识库、工具调用、浏览器/桌面生态和多语言 README；近期修复工具调用 JSON 参数保真。 | 参考 AI 工具调用参数保真、模型切换、知识库 UI、离线优先和安全报告入口；强化本项目 /api/mcp 的 schema、错误和审计。 | 我们的 AI 紧贴消息/商品上下文且有人工确认；缺少通用 AI 工作台、工具参数回显和模型会话管理。许可证不是 Apache-2.0，不能按宽松许可证直接合并。 |

## 3. 交叉对标结论

### 3.1 对方普遍更强的地方

1. 扩展边界清晰：Saleor 的 API/App、Medusa 的 Module/Provider、Vendure 的 Plugin Contract、Node-RED/n8n 的 Node/Flow 都把扩展点当成产品能力。
2. 任务可恢复：Temporal、Airflow、Kestra 保存执行历史、重试、超时、依赖和运行日志；本项目已有重试/死信，但应继续强化执行实例、取消、回放和版本。
3. 协作型消息工作台：Chatwoot 把队列、分配、备注、标签、快捷回复、客户资料和报表放在同一会话模型；本项目仍以单人卖家操作为主。
4. 可观察的 AI：Dify、Open WebUI、Chatwoot Captain 强调模型/知识库/工具调用的可追踪错误和人工介入；本项目需要保留 prompt/version/引用/成本/延迟/降级原因。
5. 分析和订阅：Metabase、Airflow、ERPNext 将指标、告警、订阅、回填和权限做成系统能力；本项目 dashboard 仍偏固定页面。
6. 部署和生态：WooCommerce、Odoo、ERPNext、n8n、Appsmith 有大量集成、扩展、文档或模板；本项目生态和第三方连接器数量有限。

### 3.2 本项目当前明确领先或应保持的能力

- 闲鱼账号、Cookie/Token、WebSocket 心跳与连接健康、风险暂停/冷却是平台专用能力。
- 自动发货、卡密库存预占/回滚、图片/文字/自定义内容、发货风控和离线 API 兜底形成了数字商品闭环。
- 本地 SQLite、浏览器访问、Docker/JAR 部署让个人卖家可以不依赖业务 SaaS。
- AI 回复与规则话术降级、人工确认、Webhook 签名/重试/SSRF 防护、监控告警和带签名更新说明体现了较强安全意识。
- 项目使用 Apache-2.0，适合在许可证兼容的前提下重新实现借鉴方案。

### 3.3 不应盲目复制的内容

- 不应因为 Saleor/Medusa/Vendure 使用 GraphQL 就整体改写当前 REST API；可先为稳定领域增加版本化 API 或只读 GraphQL/MCP 适配层。
- 不应直接引入 Temporal/Airflow/Kestra/n8n 这类大型运行时；本地 SQLite 与单机部署应优先保持，先吸收其状态模型、可观测性和声明式定义。
- 不应把 GPLv3/AGPL、Dify 附加许可、n8n Sustainable Use License、Open WebUI 附加许可的代码直接复制到 Apache-2.0 主仓库。
- 不应为了“多平台”引入未验证的闲鱼协议或绕过平台安全机制；连接器必须限于用户有权管理的账号，并保留速率、冷却和审计。

## 4. 面向本项目的升级建议（供 UPGRADE_ROADMAP.md 引用）

### P0 / P1

- 可恢复任务执行协议：为发货、Webhook、评价、批量上架和更新任务统一 job id、状态机、租约、取消、超时、重试原因和执行历史。
- 会话工作台：未读/待处理队列、标签、内部备注、快捷回复、人工接管、分配规则、客户画像和会话审计。
- AI 可观测性：记录模型、Provider、prompt 版本、知识库引用、工具调用、耗时、token/成本（若可用）、降级原因和人工确认结果。
- Webhook/连接器契约：统一事件 envelope、版本、签名、幂等键、重放和失败分类；连接器采用 SPI/Provider 接口。
- 敏感操作和权限矩阵：按账号、商品、发货、AI、更新、备份、Webhook 做细粒度权限和确认。
- 备份恢复演练：备份校验、版本标记、加密/权限、恢复前预览、SQLite 一致性检查和回滚记录。

### P2

- 有限流程 DAG：先支持任务节点、条件、串并行、人工确认、定时触发、输入/输出和版本草稿；不引入大型编排集群。
- 声明式模板/Provider：商品发布模板、回复策略、发货策略和通知渠道统一 JSON Schema + 版本迁移。
- 运营指标语义层：定义订单、发货、响应、库存、失败率等指标，支持筛选、订阅和告警。
- 可配置 dashboard：组件化小部件、保存视图、权限过滤和空/错误/过期数据提示。
- API/MCP 稳定性：命令 schema、版本、错误码、权限上下文、超时、幂等和审计。
- 集成目录：邮件、Webhook、代理、AI Provider、对象存储、通知渠道采用可发现的健康检查和配置验证。
- 安全供应链：依赖锁定、定期审计、SBOM、许可证报告、构建产物签名和更新失败回滚。

## 5. 研究限制

- 由于时间和网络访问限制，本次未对 18 个项目全部做本地克隆、完整构建和真实运行；能力主要由官方 README、目录结构、许可证和提交记录确认。
- GitHub 提交时间以 UTC 返回，报告统一按 2026-11-16 研究日记录；活跃度不等于每项功能都经过运行验证。
- 一些仓库采用多许可证或商业扩展（Medusa、Chatwoot、Dify、n8n、Metabase、Open WebUI、Vendure 等）；任何移植前都必须进行逐文件许可证审查。
- 研究重点放在可迁移产品/架构方案，未声称这些项目与闲鱼平台有官方集成，也未验证任何绕过平台限制的自动化能力。
- 本轮研究以 GitHub 官方仓库为主，未对 Gitee、GitLab、Codeberg 和 SourceForge 做同等深度的逐仓库核验；后续可针对中文卖家工具和闲鱼 SDK 做独立检索。

## 6. 结论

本项目不是缺少功能数量，而是需要把已有的闲鱼专用能力组织成更稳定、可扩展、可观察的产品边界。最值得吸收的是：

1. Saleor/Vendure/Medusa 的模块与扩展契约；
2. Temporal/Airflow/Kestra 的可恢复执行和运行历史；
3. Chatwoot 的协作式会话工作台；
4. Dify/Open WebUI 的 AI 工具、知识库和错误可观测性；
5. Metabase/Appsmith 的可配置指标和安全查询；
6. WooCommerce/Odoo/ERPNext 的 API、权限、库存和审计治理。

下一步应以本报告为证据基础，在 UPGRADE_ROADMAP.md 中建立至少 30 个独立 EV 项目，优先落地 P0/P1 的任务恢复、会话协作、AI 可观测性、连接器契约和备份恢复，而不是整体替换技术栈。
