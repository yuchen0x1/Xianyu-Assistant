# 安全架构报告：开源边界与服务端能力分离改造（2026-09）

> 当前产品范围（2026-11-13）：仅维护网页版，Android/iOS 工程与独立桌面启动包装已移除。下文的原生移动端功能和检查结果仅为旧版本历史记录，不代表当前提供独立客户端。电脑和手机均通过浏览器访问。

> 本文是对「开源代码可以公开、可以审计、可以二次开发；但官方服务能力、密钥体系与供应链信任不可被单纯复制源码后获得」这一战略的落地报告。改造基于对当前代码库的全面审计，所有条目均对应真实代码位置，不是理论方案。

## 一、项目形态与边界结论

本项目是**本地/自托管形态的单体应用**（Java 21 + Spring Boot 后端 + Vue 3 前端 + Flutter WebView 壳），服务绑定 `127.0.0.1`（可配置），数据存本地 SQLite。它不依赖任何"官方云"即可完整工作：AI 能力使用用户自配的 API Key，闲鱼连接使用用户自己的 Cookie。

因此，本项目"官方服务能力"的真实载体是两条：

1. **官方发布与更新通道**（GitHub Release + 应用内自更新 + install.sh）——这是唯一由官方运营、且所有用户默认依赖的基础设施；
2. **用户侧凭据与密钥的存储边界**（本地服务端进程持有并加密落库，浏览器/移动端永不接触）。

据此划分：

### A. 保持开源（当前已满足，无需迁移）

| 能力 | 位置 |
| --- | --- |
| UI（Vue 3 全部页面）、Flutter 移动壳 | `vue-code/`、`mobile/` |
| 业务框架（消息/发货/卡密/任务/Webhook/监控） | `src/main/java/.../` |
| 数据库 Schema 与迁移 | `src/main/resources/db/migration/`、`sql/schema.sql` |
| 本地部署能力（Docker/install.sh/systemd） | `Dockerfile`、`install.sh`、`packaging/` |
| MCP 工具端点、Webhook、通知集成 | `mcp/`、`webhook/`、`notify/` |

### B. 服务端边界（浏览器/移动端永远不接触，当前已满足）

| 能力 | 实现位置 | 结论 |
| --- | --- | --- |
| AI Provider 密钥与出站调用 | `config/rag/DynamicAIChatClientManager.java`（后端进程内） | 客户端只见 `/ai/*` 接口与 `apiKeyConfigured` 布尔值，密钥永不下行（`SysSettingServiceImpl` 统一掩码 `********` 下发） |
| 闲鱼 Cookie/Token | `entity/XianyuCookie.java`（AES-GCM 加密落库） | 浏览器永不接触明文；备份默认不导出凭据 |
| 登录会话与 RBAC | `interceptor/AuthInterceptor.java`、`rbac/` | 授权判定全部在服务端，前端只保存 Bearer Token |
| 敏感配置加密 | `security/CredentialCipher.java`（AES/GCM） | 密钥来自环境变量或本地密钥文件 |

**需要说明的平台常量**：`constants/XianyuProtocolProfile.java` 中的 `34839810`（淘宝 H5 appKey）、`444e9908...`（IM 网关 app-key）、`xy_chat` 为闲鱼/淘宝公开网页端常量，非用户私密凭据，属协议实现的一部分，可开源。

## 二、审计结论（改造前）

- **仓库与 Git 历史无泄露密钥**：对全量历史 diff 扫描 `sk-`、`LTAI`、`ghp_`、`github_pat_` 等模式，无命中；`dbdata/`、`logs/` 已正确 gitignore（含 JWT 密钥、凭据加密密钥、SQLite 库的真实文件均未入库）。
- **配置面干净**：`application.yaml` 无硬编码密钥；CI 使用平台 Secret、action 按 SHA 固定、最小权限；`deploy-production.yml` 用自托管 runner + `environment: production` 隔离。
- **前端/移动端无明文回显**：AI Key 等敏感配置读取统一掩码，Android 签名密钥从外部 `key.properties` 加载、缺文件拒绝构建。

## 三、本次修复的安全缺陷

### 3.1 AI 服务端点 SSRF / 凭据外带（高危，已修复）

**缺陷**：`ai_base_url` / `ai_embedding_base_url` 可被配置为任意 URL，服务端随后把已保存的 AI API Key 以 `Authorization: Bearer` 发往该地址。持有 `settings:write` 的 OPERATOR 可借服务端探测内网并外带管理员配置的 Key。

**修复**（三道防线 + 一道权限闸）：

1. 新增 `security/AiEndpointValidator.java`：强制 https、禁止 user-info、解析主机全部地址并拒绝回环/私网/链路本地（含 169.254.169.254 元数据端点）/CGNAT/IPv6 ULA/IPv4 映射地址；本地模型端点经 `app.security.ai-endpoint.extra-allowed-hosts` 显式放行并记录审计日志。
2. 保存时校验：`SysSettingServiceImpl.saveSetting`（覆盖所有配置写入口）。
3. 出站构建时再校验：`DynamicAIChatClientManager.buildChatClients`、`DynamicVectorStoreManager.buildVectorStore`（备份导入直接写库，绕过保存路径，故兜底必须存在）。
4. 权限闸：`SysSettingController.saveSetting` 对两个端点键要求 ADMIN 角色（RBAC 中 OPERATOR 持有 `settings:write`，原先可改端点）。

配置：`XIANYU_ASSISTANT_AI_EXTRA_ALLOWED_HOSTS`（见 `application.yaml`）。测试：`AiEndpointValidatorTest`（11 例，仅用本机可解析地址，不依赖外网 DNS）。

**已知残留风险**：校验时与实际请求时的 DNS 解析存在理论上的 rebinding 窗口（保存时解析为公网、使用时记录可能变化）。彻底闭合需要出站代理固定解析结果，见第五节。

### 3.2 发布物签名缺失，供应链信任依赖单一 GitHub 通道（高危，已修复）

**缺陷**：自更新与 install.sh 只校验 SHA-256，而 `.sha256` 校验文件与安装包同源于同一个 GitHub Release。发布通道（仓库/令牌）被入侵时，攻击者可同时替换两者，校验形同虚设。

**修复**（Ed25519 发布签名体系，非对称私钥永不下发）：

- **签名端**：`scripts/sign-release.sh`——`gen-key` 生成密钥对；`sign` 用 openssl Ed25519（`pkeyutl -sign -rawin`）产出原始签名 Base64。私钥经 `RELEASE_SIGN_KEY_FILE`（PEM 文件）或 `RELEASE_SIGN_PRIVATE_KEY`（Base64，可直接作 CI Secret）提供。
- **发布端**：`scripts/publish-github.sh` 集成签名，`.jar.sig` 随 Release 上传；**官方仓库发布未配置签名私钥时直接拒绝发布**（fail-closed）；`scripts/publish-gitee.sh` 保留为兼容入口并转发到 GitHub；社区渠道（Fork 自建发布源）可选签名。
- **验证端**：
  - Java 客户端 `security/ReleaseSignatureVerifier.java`（JDK 原生 JCA Ed25519，无自制密码学、无新依赖）；`SelfUpdateService` 在 SHA-256 通过后下载 `.jar.sig` 验签。强制模式 `app.self-update.signature-required=auto`：更新源为官方仓库 `yuchen0x1/Xianyu-Assistant` 时**必须**验签通过，缺签名/验签失败/公钥缺失一律拒绝更新；社区 Fork 默认 `auto` 不强制，保持社区版开箱可用（不破坏 Fork），可显式 `true`/`false` 覆盖。
  - `install.sh` 同步验签：内置公钥，`base64 -d` + `openssl pkeyutl -verify -rawin`；缺签名文件或验签失败中止安装（仅 openssl 过旧不支持 Ed25519 时降级为警告，应用内更新器仍强制）。
- **公钥管理**：验证公钥内置在开源代码中（`SelfUpdateService.OFFICIAL_RELEASE_PUBLIC_KEY`、`install.sh`）——公钥可公开，私钥绝不进仓库/客户端/发布物。密钥轮换可用环境变量 `XIANYU_ASSISTANT_RELEASE_PUBLIC_KEY` 覆盖。
- **CI 接线**：`.github/workflows/release.yml` 传入 `secrets.RELEASE_SIGN_PRIVATE_KEY`。

**互操作已实测**：openssl 签名 → openssl 验签 ✓；openssl 签名 → Java 验签 ✓；篡改签名 → Java 验签拒绝 ✓。

**⚠️ 维护者必须执行的密钥管理动作**（私钥是官方服务的信任根）：

1. 本次已在发布机生成密钥对，位于 `C:\Users\lenmo\.xianyu-release-signing\`（**仓库外**）：
   - `release-sign-private-key.pem`（PKCS#8）——离线/密码管理器备份一份，此后可从工作机移除；
   - `release-sign-public-key.pem`——公钥已内置进代码，无需保密。
2. 在 GitHub 仓库 Secrets 配置 `RELEASE_SIGN_PRIVATE_KEY` = 私钥 PEM 的 Base64 体（`grep -v "PRIVATE KEY" release-sign-private-key.pem | tr -d '\n'` 的输出）。
3. 若私钥疑似泄露：`sign-release.sh gen-key` 生成新对 → 更新代码内公钥常量（`SelfUpdateService` 与 `install.sh`）→ 发版。

测试：`ReleaseSignatureVerifierTest`（5 例）+ `SelfUpdateServiceTest.签名强制模式按配置与渠道判定`。

### 3.3 发布令牌经 URL/表单传递（中危，已修复）

发布脚本原以 URL query 或 multipart 表单传递令牌，会进代理/访问日志与进程列表。现 `publish-github.sh` 全部改为 `Authorization: Bearer` 请求头传递，URL 与 argv 中不再出现令牌；GitHub Actions 使用短期 `github.token`。

### 3.4 初始账号口令策略与爆破防护（中危，已修复）

- 注册口令最小长度 5 → **8**（`LoginController.MIN_PASSWORD_LENGTH`，前端注册表单同步为 8 位提示）。
- 注册接口此前完全不限流（且首注册者即 ADMIN）。现 `login`/`register` 共用严格档位（5 次/10 分钟/每 IP），`checkUserExists` 用宽松档位（60 次/10 分钟）挡脚本化探测（`AuthService.checkAttempt/recordFailure/clearFailure`，按 scope 分档计数）。

### 3.5 会话有效期与轮换（中危，已修复）

- JWT 默认有效期 30 天 → **7 天**（`jwt.expiration=604800000`，`JwtUtil` 默认值同步）。
- 新增 `POST /api/login/refresh` 会话轮换：校验当前 Token（签名有效、未过期、会话存在）后签发新 Token 并立即撤销旧 Token（单设备语义不变）。已过期 Token 不予刷新，必须重新登录。
- 前端 `request.ts`：12 小时（标签页可见时）静默续期 + 并发单飞（`refreshInFlight` 共享一次轮换，避免竞态互撤）+ 任意请求 401 时先静默续期一次并重放原请求，失败才清除凭证跳登录页。
- 反向代理真实 IP：登录限流原取 `getRemoteAddr()`，nginx 反代后所有用户共享 `127.0.0.1` 一个限流桶。新增 `app.security.trusted-proxies`（如 `127.0.0.1`）：仅当直连地址在受信名单内才采信 `X-Real-IP` / `X-Forwarded-For` 最右一跳，防头伪造绕过限流。配置 `XIANYU_ASSISTANT_TRUSTED_PROXIES`。

### 3.6 与本战略无关但审计确认的既有优点

- 无默认口令（首注册者即 ADMIN，注册互斥锁防并发多 ADMIN）；
- Cookie/Token/AI Key AES-GCM 加密落库，日志仅记录长度/布尔值（`SensitivePayloadLoggingTest`、`CredentialLoggingSafetyTest` 等基线测试持续守护——本次一条新日志因违反"异常消息不落日志"基线被测试拦截并已修正）；
- 自更新下载主机白名单 + 逐跳重定向校验 + 跨主机剥离 Authorization；`restart-mode=command` 已废弃防 shell 注入；
- Android 无 `key.properties` 拒绝 release 构建，WebView 导航白名单，release 强制 HTTPS。

## 四、开源版（社区版）与官方版的边界

改造后的分界线：

| | 社区版（Fork 自建） | 官方版（本仓库发布） |
| --- | --- | --- |
| 更新源 | 任意 GitHub 仓库（`app.update-check.github-repository`） | 官方仓库 `yuchen0x1/Xianyu-Assistant`（内置） |
| 完整性校验 | SHA-256（必须） | SHA-256（必须）+ Ed25519 验签（强制） |
| 签名能力 | 可选（`auto` 不强制；可用 `XIANYU_ASSISTANT_RELEASE_PUBLIC_KEY` 自管公钥） | 必须签名，缺签名拒绝更新/安装 |
| 官方私钥 | **不可获得**（仅 CI Secret/离线保管） | 同左（仅官方持有） |

即：**Fork 者可以复制全部源码并自建完整功能（社区版不受任何功能性阉割），但无法伪造"官方发布渠道"**——用户侧客户端只会接受官方私钥签名的官方更新包。这就是"代码可复制，服务能力（可信发布）不可复制"的落地点。

## 五、仍需后端基础设施才能实现的能力（明确边界）

以下能力**无法在纯本地形态下凭代码实现**，需真实基础设施，本次未做（也不应做半成品/假实现）：

| 能力 | 需要的基础设施 | 说明 |
| --- | --- | --- |
| 官方 AI 网关（用户免配 Key） | 云端网关服务 + Provider 凭据 Secret Manager + 配额/计费库 | 当前 AI 为 BYO-Key 模式，密钥已在服务端（本机后端）持有并加密，架构上等价于"网关在本机"；若未来提供官方代付 Key 的商业 AI 服务，需独立云端服务，客户端仅新增"AI 服务模式"指向官方端点并经 License Token 鉴权 |
| License 服务（服务端授权裁决） | 在线 License 服务 + 数据库 + 非对称签发私钥 | 当前无商业授权体系，不存在客户端 `if license == "xxx"` 逻辑（审计确认）；发布签名体系已把"官方信任根"（公钥/私钥分离）建立起来，未来 License 签发可复用同一 Ed25519 私钥体系与非对称验签模式 |
| 集中式配额/风控/防滥用 | 官方服务端 + 用户账号体系 | 本地为单机部署，限流/风控已按本机多用户 RBAC 实现（登录限流、RBAC、账号级 API 限速、认证熔断）；跨实例集中风控需官方云端 |
| 生产 Secret Manager | 云 KMS / Vault | 当前密钥管理：环境变量优先 + 本地 `dbdata/*.key` 兜底（`dbdata/` 已 gitignore 并被备份默认排除凭据）；生产部署建议显式设置 `XIANYU_ASSISTANT_JWT_SECRET`、`XIANYU_ASSISTANT_CREDENTIAL_SECRET` |
| 环境分离（dev/staging/prod） | 各环境独立部署与 Secret | 当前代码无任何环境专属硬编码（全部经环境变量注入）；CI 侧 `environment: production` + 自托管 runner 已隔离发布权限 |

## 六、残留风险与运维建议

1. **本地信任根集中**：`dbdata/` 内含 SQLite 库、JWT 密钥、凭据加密密钥。拿到整台机器文件系统即等于拿到全部状态——这是本地部署形态的固有边界，建议部署时对 `dbdata/` 做磁盘级/目录级访问控制，并在生产显式注入环境变量密钥。
2. **Token 存 localStorage**：XSS 可窃取会话 Token。本地单机 + 无第三方脚本的风险可接受；引入任何外部 JS 前应改为 HttpOnly Cookie 方案。
3. **AI 端点 DNS rebinding 窗口**：见 3.1，彻底闭合需出站固定解析。
4. **openssl <1.1.1 的 install.sh 降级**：极老系统的验签降级为警告（应用内更新器不受影响）。
5. **发版流程变更**：此后官方发版必须带 `RELEASE_SIGN_PRIVATE_KEY`（GitHub Actions Secret 或本地环境变量），否则 `publish-github.sh` 直接失败——这是有意的 fail-closed。
6. **版本衔接**：签名强制自本版本起生效；存量旧客户端无验签能力，其收到的新版本（本版本）会开始携带 `.jar.sig`（旧客户端忽略该附件，不受影响），更新一次后即获得验签能力。

## 七、变更清单

**后端**：`security/AiEndpointValidator.java`（新）、`security/ReleaseSignatureVerifier.java`（新）、`service/update/SelfUpdateService.java`、`config/rag/DynamicAIChatClientManager.java`、`config/rag/DynamicVectorStoreManager.java`、`service/impl/SysSettingServiceImpl.java`、`controller/SysSettingController.java`、`service/AuthService(Impl).java`、`controller/LoginController.java`、`util/JwtUtil.java`、`application.yaml`

**前端**：`vue-code/src/utils/request.ts`（静默续期 + 401 重放）、`vue-code/src/views/login/index.vue`（口令 8 位）

**脚本/CI**：`scripts/sign-release.sh`（新）、`scripts/publish-github.sh`（GitHub API、签名与令牌 Header）、`scripts/publish-gitee.sh`（兼容包装入口）、`install.sh`（GitHub Release + 验签）、`.github/workflows/release.yml`（GitHub token 与签名 Secret）

**测试**：`AiEndpointValidatorTest`（11）、`ReleaseSignatureVerifierTest`（5）、`AuthServiceImplTest` 新增 4 例（轮换/拒绝/分档限流）、`SelfUpdateServiceTest` 新增 1 例（强制模式判定）、`request.test.ts` 新增 2 例（401 重放/续期失败清理）

**验证结果**：后端 `mvn test` 522 例全绿；前端 `vitest` 32 例全绿 + `vue-tsc` 类型检查通过；openssl↔Java 签名互操作实测通过；`bash -n` 全部脚本语法通过。
