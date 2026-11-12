# 闲鱼卖家运营控制台 v3.5.22

> Release: 2026-11-12

本版本接入可验证的在线自动更新链路，并补齐稳定版、Beta 和 Dev 更新通道。

## ✨ 版本亮点

- 在线更新支持下载重试、取消、磁盘空间预检、真实速度和预计剩余时间。
- 下载完成后执行 SHA-256 校验与官方 Ed25519 签名校验，验证通过后才原子替换并重启。
- GitHub Release 工作流发布前执行后端测试、前端测试和 TypeScript 类型检查。
- 新增 [`docs/AUTO_UPDATE.md`](docs/AUTO_UPDATE.md)，说明配置、发布、回滚和外部验证边界。

## 验证情况

- 后端 690 项测试通过，3 项跳过。
- 前端 40 项测试通过，type-check 和生产构建通过。

# 闲鱼卖家运营控制台 v3.5.21

> Release: 2026-11-12

本版本聚焦账号隔离、安全策略和批量操作可靠性：修复数据面板跨账号可见风险，补齐批量任务的限流、上限与超时保护，并改善 Windows 浏览器探测、错误响应和构建诊断。

## ✨ 版本亮点

- 🔐 **账号范围安全修复**：数据面板统计、趋势、排行和操作日志均严格按当前用户授权账号过滤。
- ⚙️ **批量操作更可靠**：批量擦亮、下架、改价统一限制数量、限流和请求超时，一键擦亮按批次提交。
- 🪟 **Windows 浏览器兼容**：Cookie 兜底刷新可自动发现 Chrome / Edge 及 Playwright 缓存浏览器。
- 🩺 **运行诊断更准确**：未知 API 返回 HTTP 404，Actuator 路径不再被 SPA 回退吞掉，`/actuator/info` 展示构建版本与时间。

## 安全与账号隔离

- 修复数据面板 Mapper 同名重载导致账号范围查询被静默覆盖的问题，非管理员不会再看到未授权账号的统计与排行数据。
- 新增 Mapper 重载防腐测试和双账号 SQLite 装配级范围回归测试，覆盖管理员、空范围和单账号三种结果。
- 注册与修改密码共用同一套口令策略，修改密码不再绕过注册时的最小长度要求。

## 批量操作与平台兼容

- 批量擦亮增加单批 50 件上限和独立限流；留空商品列表的“擦亮全部在售”分支同样受上限约束。
- 批量下架、批量擦亮和批量改价使用 120 秒请求超时，避免前端 30 秒超时后服务端仍继续执行造成重复操作。
- 一键擦亮在售商品按 50 件分批串行提交，大账号不会因一次请求过大直接失败。
- Windows / macOS 按平台探测系统 Chrome、Edge、Playwright 缓存目录，找不到浏览器时明确提示安装指引。

## 错误响应与可观测性

- 未知 `/api/` 路径返回 HTTP 404 和 `code=404`，不再包装为 HTTP 200。
- `/actuator/` 路径排除 SPA 回退；Prometheus 默认不开放，可通过 `XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS` 显式启用。
- `/actuator/info` 新增 `build.version` 与 `build.time`，便于确认实际运行的构建。
- CI 增加前端 lint 检查，并清理未使用的 Pinia、死代码和无引用组件。

## 升级影响

- 无数据库迁移、无破坏性配置变更。
- 批量改价只更新助手本地商品资料；闲鱼远程商品仍需在官方页面确认。
- 默认 Actuator 暴露端点仍为 `health,info`；需要 Prometheus 时请显式设置暴露列表。

## 下载与安装

| 平台 | 文件名称 | 架构 | 说明 |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.21.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar) | x86_64 / arm64 | 完整服务端 JAR，已内置前端页面 |
| 校验文件 | [XianYuSellerConsole-3.5.21.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sha256) | — | SHA-256 校验值 |
| 签名文件 | [XianYuSellerConsole-3.5.21.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sig) | — | Ed25519 官方签名 |

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

**从 v3.5.20 升级**

- 推荐使用应用内在线更新、自动更新或重新执行 `bash install.sh`。
- 升级前备份 `dbdata/`，尤其是 SQLite 数据库、JWT 密钥和凭据密钥文件。
- 使用默认 `exit` 重启模式时，确认 systemd / Docker / 守护循环具备自动重启能力。

## 系统要求

- **Java 21**（必需；`install.sh` 可自动安装）
- **Docker 20.10+**（仅容器部署需要）
- SQLite 为内置存储，无需外部数据库
- 默认端口 `12400`，浏览器访问 Web 控制台

## 更新日志

完整提交对比：[v3.5.20...v3.5.21](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.20...v3.5.21)

**验证情况**：后端全量测试、前端 Vitest / type-check / ESLint / 生产构建、Flutter `analyze` / `test`、Android debug APK 均已通过；Docker Linux engine 仍受本机 Docker Desktop 残留 socket 问题影响，未作为项目构建失败处理。
