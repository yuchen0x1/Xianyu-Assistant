# 闲鱼卖家运营控制台

> 把盯消息、回客户、发卡密、管库存这些重复劳动，交给一台你自己的电脑。

「闲鱼卖家运营控制台」（项目历史名称：「闲鱼自动发货助手」）是一个面向**自有闲鱼账号**的本地化运营工具：账号连接、商品管理、消息处理、自动回复、自动发货、卡密库存、任务调度、Webhook、数据备份与运行监控一站式覆盖，支持同时管理多个账号。数据保存在本地 SQLite 中，不依赖第三方服务器。

- 当前版本：`v3.4.10`，正式发布
- 本仓库托管于 GitHub（`yuchen0x1/Xianyu-Assistant`），基于上游项目持续二次开发，上游来源与版权说明见文末《二次开发声明》

## 一、它能帮你做什么

### 账号与连接

- **闲鱼账号**：扫码登录、本机浏览器登录接管、手动添加 Cookie、多账号并行；支持账号级风控事件记录与自动化暂停/冷却
- **连接管理**：Cookie/Token 管理、WebSocket 长连接、心跳保活、自动重连、健康状态

### 商品与发布

- **商品管理**：同步商品、编辑本地资料、SKU 管理、价格历史、商品状态筛选
- **发布模板**：商品发布模板 CRUD、CSV 批量导入模板、模板批量上架

### 消息与智能回复

- **消息管理**：会话列表、历史消息、文字/图片回复、人工接管、关键词回复
- **自动回复**：关键词规则、延时回复、AI 回复、知识库上下文（RAG）、AI 回复人工确认模式、智能议价（保底价/让价步长/轮数限制，AI 不可用时自动降级话术）

### 发货与卡密

- **自动发货**：文本、图片、卡密、自定义内容发货；失败重试、库存预占与回滚、发货风控规则（按账号配置阻断条件）、断线后 API 兜底发货（默认关闭）
- **卡密仓库**：批量导入、库存管理、预占/发放、使用记录、低库存告警
- **发货记录**：发货流水、状态筛选、Excel 导出

### 自动化与集成

- **任务中心**：任务定义、幂等执行、失败重试与退避、死信队列、补偿任务
- **自动评价**：按账号配置评价模板、延迟调度、人工确认/取消/重试、评价记录观察；平台能力未接入时不伪造成功，明确转人工处理
- **计划任务**：定时任务管理（Cron 表达式、启停、失败恢复）
- **命令中心**：内置 6 个自动化命令（发布商品、同步商品、下架、发送消息、商品列表、账号列表），并暴露轻量 MCP 服务端点（`/api/mcp`，JSON-RPC 2.0），可将内置命令作为 AI 工具调用
- **Webhook**：回调地址配置、HMAC-SHA256 签名校验、事件推送（发货完成/失败、订单创建、连接断开/恢复、告警、库存不足、测试）、SSRF 防重绑定、投递历史（outbox）持久化与指数退避重试
- **监控中心**：告警持久化/去重/抑制窗口、确认/静默/恢复状态机、`/monitoring` 页面（健康概览 + 告警列表 + 处理操作）

### 数据与安全

- **数据面板**：商品、订单、消息、发货成功率、AI 回复等运营数据统计与可视化
- **权限与安全**：登录限流、RBAC 权限、敏感凭据加密落库、操作日志审计
- **数据与运维**：全量备份与恢复（订单/聊天/通知/任务/发布历史等 12 模块，默认不导出明文 Cookie/Token，可显式包含）、Excel 导出、代理池（默认关闭）、健康检查、Prometheus 指标、多渠道通知、在线更新检查

## 二、技术选型

- **后端**：Java 21、Spring Boot 3.5、MyBatis-Plus、SQLite、WebSocket、Playwright、OkHttp、JJWT、EasyExcel
- **前端**：Vue 3.5 + TypeScript + Vite + Pinia + Vitest
- **AI 能力**：Spring AI（OpenAI 兼容接口，默认 DashScope）+ 本地向量知识库
- **可观测性**：Spring Boot Actuator + Micrometer + Prometheus
- **部署形态**：JAR / Docker / systemd + Nginx 反向代理

## 三、上手指南

### 环境要求

- Java 21（必需）
- Node.js `20.19+` 或 `22.12+`（仅构建前端需要）
- Docker 20.10+（仅容器部署需要）

### 获取代码

```bash
git clone https://github.com/yuchen0x1/Xianyu-Assistant.git
cd Xianyu-Assistant
```

### 部署与运行

#### 方式一：Docker（推荐）

Dockerfile 为多阶段构建，会自动编译前端再打包后端，无需本地准备构建产物：

```bash
docker build -t xianyu-seller-console:local .
docker run -d \
  -p 127.0.0.1:12400:12400 \
  -v $(pwd)/dbdata:/app/dbdata \
  xianyu-seller-console:local
```

#### 方式二：Linux 一键安装

```bash
# 下载脚本后本地执行（支持 Debian/Ubuntu/CentOS/Fedora/Arch Linux/macOS/Termux，自动安装 JDK 21）
bash install.sh
```

> 注意：`install.sh` 从 GitHub Release 下载最新 JAR，仓库需要先发布带 JAR 附件、`.sha256` 和官方 `.jar.sig` 的 Release 才能使用（CI 中的 `release.yml` 已配置自动发布流程）。

#### 方式三：Windows 本地开发

先构建后端 JAR 并安装前端依赖：

```powershell
.\mvnw.cmd -B -ntp -DskipTests package
cd vue-code
npm ci
cd ..
```

启动开发环境（脚本会先检查端口占用，前端退出时自动停掉后端）：

```powershell
.\scripts\start-dev.ps1
```

- 后端默认监听 `127.0.0.1:12400`
- 开发前端默认监听 `127.0.0.1:5180`（由 `start-dev.ps1` 指定；单独运行 `npm run dev` 时按 `vue-code/vite.config.ts` 的默认端口 `5173`），代理转发到后端
- 停止开发环境：`.\scripts\stop-dev.ps1`

Linux/macOS 下可分别启动后端（`./mvnw spring-boot:run`）和前端（`cd vue-code && npm run dev`）。

#### 方式四：源码打 JAR 部署

前端构建产物（`src/main/resources/static/`）不入库，源码手工部署需先构建前端，否则 JAR 内不包含页面：

```bash
cd vue-code && npm ci && npm run build && cd ..
./mvnw -B -ntp -DskipTests clean package
java -jar target/XianYuSellerConsole-*.jar   # JAR 文件名由 pom.xml 版本决定
```

> 使用 Docker 或官方 Release 下载的 JAR 已内置前端，无需重复构建。

systemd 托管、Nginx 反向代理、生产环境部署的具体步骤见[《生产环境部署》](docs/production-deployment.md)。服务器地址、SSH 用户与端口属于部署机密，不写入公共仓库。

#### 运行测试

```bash
# 后端：JUnit 测试套件
.\mvnw.cmd test

# 前端：Vitest
cd vue-code && npm run test
```

也可以在 Windows 上运行统一验收脚本，一次执行后端测试、前端测试/类型检查/生产构建，以及移动端分析、测试和 Android Debug 构建：

```powershell
.\scripts\check-project.ps1
```

如果只需要跳过耗时的 Android 构建，可使用 `.\scripts\check-project.ps1 -SkipAndroidBuild`。脚本不会删除或覆盖 `dbdata`。

## 四、配置参考

### 默认参数

- **后端端口**：`12400`，默认只监听本机
- **前端端口**：`5180`（开发环境）
- **数据库**：SQLite（`dbdata` 目录）；自动备份默认每 12 小时执行一次（首次启动 60 秒后执行），保留 14 份
- **管理员账号**：首次启动时初始化，由你自行设置
- **AI 默认配置**：base_url=`https://dashscope.aliyuncs.com/compatible-mode`、model=`deepseek-v3`；未配置 API Key 时 AI 功能自动降级
- **Playwright**：`1.62.0`（Docker 运行时镜像同步为 `v1.62.0-noble`），用于浏览器登录接管与闲鱼页面自动化
- **局域网访问**：默认关闭，需显式设置 `XIANYU_ASSISTANT_BIND_ADDRESS`

### 环境变量

以下变量均可通过环境变量注入（完整列表见 `src/main/resources/application.yaml`）：

- `XIANYU_ASSISTANT_BIND_ADDRESS`（默认 `127.0.0.1`）：监听地址，设为 `0.0.0.0` 可开启局域网访问
- `XIANYU_ASSISTANT_JWT_SECRET`（默认空，自动生成到密钥文件）：生产环境建议设置至少 32 字节的随机值
- `XIANYU_ASSISTANT_JWT_SECRET_FILE`（默认 `dbdata/jwt-secret.key`）：JWT 密钥持久化文件
- `XIANYU_ASSISTANT_BACKUP_ENABLED` / `XIANYU_ASSISTANT_BACKUP_INTERVAL_MS` / `XIANYU_ASSISTANT_BACKUP_DIRECTORY` / `XIANYU_ASSISTANT_BACKUP_RETENTION_COUNT`（默认 `true` / `43200000` / `dbdata/backups` / `14`）：自动备份开关、间隔、目录与保留份数
- `XIANYU_ASSISTANT_UPDATE_CHECK_ENABLED`（默认 `true`）：在线更新检查（读取 GitHub Release）
- `XIANYU_ASSISTANT_UPDATE_GITHUB_REPOSITORY`（默认 `yuchen0x1/Xianyu-Assistant`）：GitHub 更新源仓库（当前更新检查使用公开 Release API，不配置仓库令牌）
- `XIANYU_ASSISTANT_API_DELIVERY_FALLBACK_ENABLED` / `XIANYU_ASSISTANT_API_DELIVERY_FALLBACK_ACCOUNT_IDS`（默认 `false` / 空）：API 兜底发货开关与允许的账号 ID 白名单
- `XIANYU_ASSISTANT_IMAGE_DOWNLOAD_ALLOWED_HOSTS`（默认 `alicdn.com,goofish.com`）：URL 代传图片仅允许的可信域名（防 SSRF）
- `XIANYU_ASSISTANT_TOKEN_REFRESH_POOL_SIZE`（默认 `2`）：Cookie 保活线程池大小
- `XIANYU_ASSISTANT_AI_CONNECT_TIMEOUT_SECONDS` / `XIANYU_ASSISTANT_AI_RESPONSE_TIMEOUT_SECONDS`（默认 `5` / `35`）：AI 调用连接与响应超时

### 凭据安全

Cookie、Token、AI 密钥、SMTP 密码和通知渠道密钥落库时使用 AES/GCM 加密（`CredentialCipher`），日志输出已脱敏。请勿把敏感信息提交到 Git、截图或发到群聊。

## 五、目录导览

```
├── src/main/java/com/feijimiao/xianyuassistant/   # 后端源码
│   ├── controller/          # REST API（登录/账号/商品/订单/消息/发布/模板/卡密…）
│   ├── service/             # 业务逻辑（自动发货/自动回复/备份/通知/代理池…）
│   ├── adapter/xianyu/      # 闲鱼协议适配层（统一接口与降级）
│   ├── webhook/  rbac/  job/  monitor/  notify/  proxy/  risk/  mcp/   # Webhook、权限、任务引擎、监控、告警、代理池、风控、MCP 端点
│   ├── utils/  config/  event/  constant/   # 工具、配置、事件、常量
│   └── command/             # 命令中心（内置命令实现）
├── src/main/resources/static/    # 前端构建产物（不入库，构建时生成）
├── vue-code/                     # Vue 3 + TypeScript 前端源码
├── docs/                         # 部署、浏览器登录、多账号接入等操作文档
├── scripts/                      # Windows 开发脚本（start-dev / stop-dev / 导出会话）
├── dbdata/                       # SQLite 数据库、密钥文件与自动备份（不入库）
├── install.sh                    # Linux/macOS/Termux 一键安装脚本
├── Dockerfile                    # 多阶段构建（前端 → JAR → Playwright 运行时）
├── .github/workflows/            # CI / Release / 生产部署流水线
├── CHANGELOG.md                  # 更新日志
└── LICENSE                       # Apache-2.0
```

## 六、进阶文档

- 生产环境部署（systemd + Nginx）：[docs/production-deployment.md](docs/production-deployment.md)
- 本机浏览器登录接管说明：[docs/local-browser-login.md](docs/local-browser-login.md)
- 多账号接入与并行验证手册：[docs/multi-account-onboarding-2026-08-21.md](docs/multi-account-onboarding-2026-08-21.md)
- 开源项目调研与整合记录：[docs/open-source-integration.md](docs/open-source-integration.md)

## 七、常见问题

**问：首次启动后怎么创建账号？**
答：数据库为空时，页面显示的是创建账号表单，第一个注册的账号自动绑定管理员（ADMIN）角色；已有账号后页面切换为登录表单。

**问：`start-dev.ps1` 提示 "Backend jar is missing"？**
答：先执行 `.\mvnw.cmd -B -ntp -DskipTests package` 生成 JAR，并确认前端依赖已安装（`cd vue-code && npm ci`）。

**问：端口被占用怎么办？**
答：脚本会提示占用端口的进程并退出，可通过 `.\scripts\start-dev.ps1 -FrontendPort 5181 -BackendPort 12401` 指定其他端口。

**问：登录提示"登录错误次数过多"？**
答：连续登录失败会触发 10 分钟 IP 限流，等待后重试即可。

**问：AI 回复不可用？**
答：AI 默认使用 DashScope 兼容接口，需在系统设置中配置 API Key；未配置时 AI 功能自动降级，关键词回复不受影响。

**问：页面提示检查更新失败？**
答：更新检查请求 GitHub Release API，仓库未发布 Release 或网络不通时会失败，不影响其他功能。

**问：JAR 部署后打开页面空白 / 只有接口没有界面？**
答：前端构建产物（`src/main/resources/static/`）不入库。源码手工部署需先执行 `cd vue-code && npm ci && npm run build` 再打包，或改用 Docker / 官方 Release 产物（已内置前端）。

## 八、安全须知

- 数据库中的 Cookie、Token、AI 密钥、SMTP 密码等敏感信息使用 AES/GCM 加密存储，证书密钥文件位于 `dbdata/credential-secret.key`，请勿外泄或提交到 Git。
- JWT 登录密钥默认自动生成到 `dbdata/jwt-secret.key`；生产环境建议通过环境变量 `XIANYU_ASSISTANT_JWT_SECRET` 显式指定。
- Webhook 回调地址仅允许 http/https，且会校验解析后的 IP 不得指向本机、环回或内网（SSRF 防护）；图片 URL 代传仅允许配置的可信域名。
- 请勿把账号 Cookie、密钥截图或发送到群聊、工单等公开渠道；日志中的敏感字段已做脱敏。

## 九、二次开发声明

本项目是 `IAMLZY2018/XianYuAssistant`（原创作者：`IAMLZY2018`，上游原始仓库位于 GitHub，352+ stars，Apache-2.0 协议）的二次开发版本，与上游项目不是同一个发布仓库。上游原始地址：`https://github.com/IAMLZY2018/XianYuAssistant`

本仓库在上游基础上新增/重构的主要内容：

- 闲鱼协议适配层（`adapter/xianyu`，统一接口 + 接口失效降级）
- 商品发布模板与 CSV 批量导入/批量上架
- AI 回复人工确认模式
- Excel 导出（订单/商品/卡密）
- RBAC 权限体系
- 任务引擎与死信队列
- Webhook 事件推送（含 SSRF 防护）
- 数据自动备份与恢复
- 代理池
- API 兜底发货
- 操作日志审计

同时持续进行 bug 修复与维护。

代码沿用与上游一致的 [Apache License 2.0](LICENSE)。使用时请保留上游版权声明与本说明。

## 十、反馈与交流

- QQ 交流群：`1016190748`
- Telegram：[https://t.me/lenmoC](https://t.me/lenmoC)

反馈问题时请注明使用的版本、运行平台和具体现象；涉及上游项目时请一并说明原项目地址 `https://github.com/IAMLZY2018/XianYuAssistant` 与原创作者 `IAMLZY2018`。

## 附：更新日志

完整版本历史见 [CHANGELOG.md](CHANGELOG.md)（当前版本 `v3.4.10`）。
