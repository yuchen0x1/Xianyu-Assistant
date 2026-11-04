# 闲鱼卖家运营控制台 v3.5.17

> Release: 2026-11-04

本次版本为服务器部署带来**无人值守自动更新**闭环，并修复自动发货在异常发送结果下的误判行为，同时完成一轮 CI 供应链安全加固。

## ✨ 版本亮点

- 🤖 **无人值守自动更新**：定时检查官方 GitHub Release，自动完成 下载 → 验签 → 替换 → 重启 全流程，服务器可长期免维护运行。
- 📦 **自动发货可靠性修复**：WebSocket 发送结果未知时不再误判为「发货失败」，转为「待人工复核」并告警，避免重复发货或漏记。
- 🔐 **供应链安全加固**：CI Action 固定 commit SHA、Docker 镜像构建时运行后端测试、新增漏洞报告政策与安全审计报告。
- 🏗️ **更新检查逻辑服务化**：抽出独立 `UpdateCheckService`，供控制器与自动更新调度器共用，端点行为不变。

## 新增功能

### 🤖 服务器无人值守自动更新

新增 `SelfUpdateAutoScheduler` 定时调度器（默认**开启**）：

- 默认每 **6 小时**检查一次官方 GitHub Release（启动 5 分钟后首检）。
- 发现新版本后自动执行：**下载 → SHA-256 + Ed25519 双重验签 → 原子替换（失败自动回滚）→ 按 `restart-mode` 重启**。
- `exit` 模式（默认）配合 systemd `Restart=always`，即可实现「自动更新 + 更新后自动重启」闭环，全程无需人工干预。

相关配置（环境变量）：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED` | `true` | 自动更新开关，设为 `false` 关闭 |
| `XIANYU_ASSISTANT_AUTO_UPDATE_CHECK_INTERVAL_MS` | `21600000` | 检查间隔（毫秒），默认 6 小时 |
| `XIANYU_ASSISTANT_AUTO_UPDATE_INITIAL_DELAY_MS` | `300000` | 启动后首检延迟（毫秒），默认 5 分钟 |

> 应用内「手动在线更新」（`XIANYU_ASSISTANT_SELF_UPDATE_*`）不受影响；自动更新仅在以独立 JAR 方式运行时可用。

## 问题修复

### 📦 自动发货：未知发送结果不再误判

- 新增 `DeliveryOrderState`，集中管理发货订单状态与「待人工复核」判定，替换裸 `-1/0/1` 字面量。
- WebSocket 发送结果为 **UNKNOWN**（帧可能已写出、成败未确认）时，此前可能被当作失败处理；现在统一持久化为 **PENDING 待人工复核**，并在复核状态持久化失败时主动告警，避免同一订单重复发货或漏记。
- `MessageSendResult` 语义澄清：UNKNOWN 表示发送结果未确认，调用方不得假定失败。

## 功能优化

### 🏗️ 更新检查逻辑抽取为 `UpdateCheckService`

- 原 `SystemController` 内的在线更新检查（GitHub/Gitee 发布源解析、下载主机白名单、5 分钟缓存、版本比较）整体迁移为独立服务。
- `/api/system/checkUpdate` 端点行为不变，供控制器与自动更新调度器共用。

### 📖 文档完善

- `docs/production-deployment.md` 补充 systemd 单元自动重启要求（`Restart=always`）与应用内自动更新说明。
- README 配置参考补齐 `XIANYU_ASSISTANT_SELF_UPDATE_*` 与 `XIANYU_ASSISTANT_AUTO_UPDATE_*` 环境变量。
- 修正迁移说明中错误的仓库 slug。

## 性能与安全

### 🔐 CI 供应链加固

- `ci.yml` 中 `subosito/flutter-action` 固定为完整 commit SHA，防止 Action 标签被劫持。
- Dockerfile 构建镜像时**实际运行后端测试**（移除 `-DskipTests`），坏代码不再能进入镜像。
- 前端 `minimatch` 覆盖升级至 `^11.0.1`，消除 npm audit 发现的漏洞。
- 新增 [`SECURITY.md`](SECURITY.md)（漏洞报告政策）与 [`security-audit-report.md`](security-audit-report.md)（安全审计报告）。

## 下载与安装

| 平台 | 文件名称 | 架构 | 说明 |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.17.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar) | x86_64 / arm64 | 完整服务端 JAR（约 287 MB），已内置前端页面 |
| 校验文件 | [XianYuSellerConsole-3.5.17.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sha256) | — | SHA-256 校验值 |
| 签名文件 | [XianYuSellerConsole-3.5.17.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sig) | — | Ed25519 官方签名 |

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

**从 v3.5.16 升级**

- **推荐：应用内在线更新或自动更新**——本版本起自动更新调度器默认开启，升级到 v3.5.17 后服务器即可长期自动跟进新版本。
- `exit` 重启模式（默认）需进程管理器配合：systemd 单元请确认已配置 `Restart=always`（见 `docs/production-deployment.md`）；裸 `nohup java -jar` 部署请改用 `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`。
- 也可重新执行 `bash install.sh` 完成手动升级。
- 数据（SQLite 库、配置）位于应用目录内，升级自动保留；升级前建议照常备份 `dbdata/` 目录。

**配置迁移**

- v3.5.17 无破坏性配置变更；如不希望服务器自动更新，设置 `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false` 即可。

## 系统要求

- **Java 21**（必需；`install.sh` 可自动安装）
- **Docker 20.10+**（仅容器部署需要）
- SQLite 为内置存储，无需外部数据库
- 部署形态：JAR / Docker / systemd + Nginx 反向代理（详见 [docs/production-deployment.md](docs/production-deployment.md)）
- 默认端口 `12400`，浏览器访问 Web 控制台

## 更新日志

完整提交对比：[v3.5.16...v3.5.17](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.16...v3.5.17)

| 提交 | 类型 | 内容 |
|---|---|---|
| [`417e7cc`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/417e7cc) | feat | 无人值守自动更新调度器 + `UpdateCheckService` 抽取 |
| [`df2d575`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/df2d575) | fix | 自动发货 UNKNOWN 发送结果转「待人工复核」 |
| [`ebce71b`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/ebce71b) | chore(security) | CI 供应链加固、SECURITY.md 与安全审计报告 |
| [`f736439`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/f736439) | fix(dev) | `start-dev.ps1` 兼容含空格的项目路径 |
| [`64216ac`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/64216ac) | docs | v3.5.16 文档收尾、修正迁移说明仓库 slug |
| [`56a05a2`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/56a05a2) | chore(release) | 版本号对齐 3.5.17 |

**验证情况**：新增 `SelfUpdateAutoSchedulerTest`（13 项）、`UpdateCheckServiceTest`（迁移）、扩展 `AutoDeliveryServiceImplTest` 与 `XianyuWebSocketClientSendResultTest`；后端全量测试、前端类型检查与构建通过。
