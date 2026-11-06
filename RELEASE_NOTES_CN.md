# 闲鱼卖家运营控制台 v3.5.18

> Release: 2026-11-06

本次版本新增**商品擦亮**能力（单品 / 批量 / 定时），并对整套 Web 控制台完成**深空赛博视觉体系升级**，同时针对平台风控优化了 AI 回复延时策略。

## ✨ 版本亮点

- ✨ **商品擦亮**：闲鱼平台提供的免费曝光手段，单品一键擦亮、账号在售批量擦亮，并可用 Cron 定时自动执行。
- 🛰️ **深空赛博 UI 全面升级**：霓虹电光青 / 赛博蓝 / 电离紫三色光效体系，深空底色 + 高精悬浮磨砂玻璃，矩阵微光网格、流光按钮、雷达呼吸灯。
- 🛡️ **AI 回复延时拟人化**：高斯分布随机抖动，打散固定时间回复的机器行为指纹，降低触发平台风控概率。
- 🐛 **版本号回退值统一**：修复未注入 `app.version` 时自更新检查可能误判版本的问题。

## 新增功能

### ✨ 商品擦亮（Polishing）

擦亮会把商品重新推回平台的搜索与推荐列表前列，是平台提供的**免费公域曝光**手段。本次新增三条入口：

| 入口 | 位置 | 说明 |
|---|---|---|
| 单品擦亮 | 商品管理 → 操作列「擦亮」按钮 | 仅在售商品可见，调用平台 H5 端擦亮接口 |
| 一键批量擦亮 | 商品管理 → 页头「一键擦亮在售」 | 擦亮当前账号下全部在售商品，逐件间隔 1 秒避免频控 |
| 定时自动擦亮 | 计划任务 → 任务类型「自动擦亮」 | 支持 Cron 表达式，可指定商品 ID，留空则全量在售 |

接口说明（后端）：

- `POST /api/items/polish` —— 单品擦亮，需要 `goods:write` 权限。
- `POST /api/items/batchPolish` —— 批量擦亮，需要 `goods:write` 权限。

协议层使用闲鱼 H5 端 `mtop.taobao.idle.item.polish`（版本 `2.0`），并复用现有的账号级限速、并发上限与风控熔断保护。平台对每个商品每天的擦亮次数有上限，重复调用会返回业务错误，界面会逐条展示成功/失败明细。

> 提示：擦亮次数由平台限制，建议使用「定时自动擦亮」每日执行一次即可，无需频繁重复操作。

### 🛰️ 深空赛博视觉体系升级

整套控制台完成视觉重构，深浅两套主题同步优化：

- **光效令牌体系**：新增霓虹电光青 `#00f2fe`、赛博蓝 `#4facfe`、电离紫 `#9d4edd`、脉冲翡翠 `#10b981` 四色光效，构建统一的发光 / 辉光 / 描边语言。
- **深空底色与高精玻璃**：深色模式底色升级为 `#060a12`，面板使用 24px 高精磨砂玻璃 + 青色霓虹描边，配合三层边缘光与内阴影形成纵深。
- **矩阵微光网格**：全局背景叠加 32px 几何点阵，配合三处径向光晕，营造控制台氛围。
- **交互微动效**：主按钮流光扫过、卡片悬浮升维辉光、侧边菜单磁吸位移与霓虹能量条、服务状态雷达呼吸灯、发光滚动条、渐变裁切标题。
- **可访问性守护**：完整尊重 `prefers-reduced-motion`（减弱动效）与 `prefers-reduced-transparency`（减弱透明），系统开启对应偏好时自动降级为静态纯色界面。

## 性能与安全

### 🛡️ AI 回复延时拟人化抖动

- 此前 AI 自动回复严格按配置的固定秒数触发，整点固定间隔是典型的自动化行为特征。
- 现在 `ReplyConfigProvider.getDelaySeconds()` 在基础延时上叠加**高斯正态分布**随机因子（σ=0.15，约 95% 落在 ±25% 区间，最低保底 3 秒），形成接近真人的自然回复节奏。
- 平台侧限速、并发上限与风控熔断策略保持不变。

### 🐛 版本号回退值统一对齐

- `application.yaml`、`SystemController`、`McpServerController`、`SelfUpdateService`、`UpdateCheckService` 以及前端 `AppLayout.vue`、`UpdateDialog.vue`、`AboutPanel.vue` 中残留的旧版本回退值统一更新为当前版本。
- 修复未注入 `app.version` 环境变量时，版本信息与自更新检查可能显示旧版本号的问题。

### 🧹 代码质量

- `WebSocketController.CaptchaMouseReqDTO` 补充 `@EqualsAndHashCode(callSuper = false)`，消除 Lombok 子类编译告警。
- 前端 `useGoodsManager.ts` 的 `catch (err: any)` 改为 `catch (err: unknown)` 并做严格类型收窄，ESLint 零告警。

## 下载与安装

| 平台 | 文件名称 | 架构 | 说明 |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.18.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar) | x86_64 / arm64 | 完整服务端 JAR（约 287 MB），已内置前端页面 |
| 校验文件 | [XianYuSellerConsole-3.5.18.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sha256) | — | SHA-256 校验值 |
| 签名文件 | [XianYuSellerConsole-3.5.18.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sig) | — | Ed25519 官方签名 |

> 📦 Docker 无预构建镜像：按 `Dockerfile` 多阶段构建本地镜像（见下方安装说明）。
>
> 📱 移动端（Android）为 Flutter 源码工程，随仓库分发，Release 不提供预构建 APK。

## 安装与升级说明

**全新安装（Linux 一键脚本）**

```bash
bash install.sh
```

脚本自动安装 JDK 21、从 GitHub Release 下载最新 JAR，并强制校验 SHA-256 与 Ed25519 签名。

**Docker 部署**

```bash
docker build -t xianyu-seller-console:local .
docker run -d -p 12400:12400 xianyu-seller-console:local
```

**从 v3.5.17 升级**

- **推荐：应用内在线更新或自动更新**——自动更新调度器默认开启，升级后服务器即可长期自动跟进新版本。
- `exit` 重启模式（默认）需进程管理器配合：systemd 单元请确认已配置 `Restart=always`（见 `docs/production-deployment.md`）；裸 `nohup java -jar` 部署请改用 `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`。
- 也可重新执行 `bash install.sh` 完成手动升级。
- 数据（SQLite 库、配置）位于应用目录内，升级自动保留；升级前建议照常备份 `dbdata/` 目录。

**配置迁移**

- v3.5.18 无破坏性配置变更；本次未新增 Flyway 数据库迁移，升级后数据库结构保持兼容。
- 如不希望服务器自动更新，设置 `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false` 即可。

## 系统要求

- **Java 21**（必需；`install.sh` 可自动安装）
- **Docker 20.10+**（仅容器部署需要）
- SQLite 为内置存储，无需外部数据库
- 部署形态：JAR / Docker / systemd + Nginx 反向代理（详见 [docs/production-deployment.md](docs/production-deployment.md)）
- 默认端口 `12400`，浏览器访问 Web 控制台

## 更新日志

完整提交对比：[v3.5.17...v3.5.18](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.17...v3.5.18)

**验证情况**：后端 651 项单元测试全绿（0 失败 / 0 错误 / 3 跳过）；前端 ESLint、TypeScript 类型检查、34 项 Vitest 用例与 Vite 生产构建全部通过；Flutter `analyze` 与 `flutter test` 全绿。
