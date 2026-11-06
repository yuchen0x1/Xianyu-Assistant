# 闲鱼卖家运营控制台 v3.5.19

> Release: 2026-11-07

本版本是一个**在线自更新可用性修复版**：解决 Windows 服务器上以独立 JAR 方式部署时，「版本更新」弹窗误报「未以独立 JAR 方式运行（如 IDE 开发模式），在线更新不可用」的问题。

## ✨ 版本亮点

- 🛠️ **在线自更新在 Windows 独立 JAR 部署下恢复正常**：`java -jar` 启动的实例不再被误判为 IDE 开发模式，「检查更新 → 立即更新 → 自动下载重启」全链路可用。
- 🔧 **自拉起重启加固**：`restart-mode=self` 重启时使用当前运行时自身的 `java` 可执行文件，不再依赖 PATH。

## 问题修复

### 🛠️ Windows 独立 JAR 部署下在线自更新误判

**现象**：以 `java -jar XianYuSellerConsole-<版本>.jar` 方式运行时，「版本更新」弹窗显示「未以独立 JAR 方式运行（如 IDE 开发模式），在线更新不可用；请通过发布页手动下载」。

**根因**：旧逻辑仅依赖 `ProcessHandle.current().info().arguments()` 从进程启动参数中定位当前 JAR。Windows JVM 经常无法获取自身启动参数（返回 empty），定位失败即被误判为不可更新。Linux（主要部署平台）参数可读，故未暴露。

**修复**：JAR 定位链扩展为三级兜底：

1. 进程启动参数（`-jar` 后的路径，原逻辑）。
2. 环境变量 `XIANYU_ASSISTANT_JAR_PATH`（原逻辑）。
3. **新增**：Spring Boot `ApplicationHome` —— 从类加载来源解析实际运行 JAR 的绝对路径；IDE 开发模式下解析出的是 classes 目录（非 `.jar` 文件），自动过滤不会误判。

### 🔧 自拉起命令回退加固

`restart-mode=self` 自拉起重启时，若 `ProcessHandle...command()` 也拿不到 java 可执行文件路径，回退目标从依赖 PATH 的裸 `java` 改为 `$JAVA_HOME/bin/java(.exe)`——即当前正在运行的这份 JVM，避免重启后解析到另一个版本。

## 升级影响

- **Linux 服务器（systemd / Docker / install.sh）**：无行为变化，原逻辑本来正常。
- **Windows 独立 JAR 部署**：修复后「立即更新」恢复可用；如需自拉起重启仍建议配置进程管理器或 `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`。
- 无破坏性配置变更，无数据库迁移。

## 下载与安装

| 平台 | 文件名称 | 架构 | 说明 |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.19.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar) | x86_64 / arm64 | 完整服务端 JAR（约 287 MB），已内置前端页面 |
| 校验文件 | [XianYuSellerConsole-3.5.19.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sha256) | — | SHA-256 校验值 |
| 签名文件 | [XianYuSellerConsole-3.5.19.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sig) | — | Ed25519 官方签名 |

> 📦 Docker 无预构建镜像：按 `Dockerfile` 多阶段构建本地镜像。
>
> 📱 移动端（Android）为 Flutter 源码工程，随仓库分发，Release 不提供预构建 APK。

## 安装与升级说明

**全新安装（Linux 一键脚本）**

```bash
bash install.sh
```

**Docker 部署**

```bash
docker build -t xianyu-seller-console:local .
docker run -d -p 12400:12400 xianyu-seller-console:local
```

**从 v3.5.18 升级**

- **推荐：应用内在线更新或自动更新**。Windows 用户升级到本版本后，在线更新弹窗不再出现误报警告。
- 升级前建议照常备份 `dbdata/` 目录。
- `exit` 重启模式（默认）需进程管理器配合：systemd 单元请确认已配置 `Restart=always`。

## 系统要求

- **Java 21**（必需；`install.sh` 可自动安装）
- **Docker 20.10+**（仅容器部署需要）
- SQLite 为内置存储，无需外部数据库
- 默认端口 `12400`，浏览器访问 Web 控制台

## 更新日志

完整提交对比：[v3.5.18...v3.5.19](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.18...v3.5.19)

**验证情况**：`SelfUpdateServiceTest` 新增 5 项；后端全量 655 项测试通过（0 失败 / 0 错误 / 3 跳过）；Windows 实机验证独立 JAR 启动日志出现「在线自更新已就绪」。
