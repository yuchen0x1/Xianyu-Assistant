# 自动更新实施说明

本文档说明 XianYu Seller Console 当前已经接入的在线更新链路、发布流程和外部配置。

## 适用范围

- **后端与 Web 控制台**：以独立 Spring Boot JAR 运行时支持在线检查、下载、校验、替换和重启。
- **systemd / 守护循环**：独立 JAR 可用 `restart-mode=exit`，由外部进程管理器重新拉起进程。
- **Docker**：通过 Compose 重建、验证和回退镜像。应用内替换和定时安装默认关闭，控制台仍可检查官方版本。[Compose 部署](compose-deployment.md)
- **IDE 开发模式**：不会误判为可更新；此时只能查看 GitHub Release 并手动部署。
- **浏览器访问**：电脑与手机使用同一个 Web 服务，更新服务端后重新加载网页即可，无需安装独立客户端。

## 更新流程

1. 前端调用 `/api/system/checkUpdate`，后端读取 GitHub Release API，并缓存结果 5 分钟。
2. 只接受 HTTPS、GitHub API 和 GitHub 官方附件域名；不跟随任意外部重定向。
3. 用户确认或定时任务触发后，后端下载目标 JAR。网络失败最多自动重试 3 次，镜像失败会回退到官方附件地址。
4. 下载过程中检查响应大小、真实字节数、磁盘可用空间，并返回字节数、百分比、速度和预计剩余时间。
5. 下载完成后必须通过 Release 附带的 SHA-256 文件。官方仓库还必须通过内置 Ed25519 公钥验签。
6. 校验通过后核对 JAR 清单的 `Implementation-Version` 与更新目标，再保留 `.bak` 并替换 JAR。
7. Windows 当前进程无法覆盖正在使用的 JAR 时，会把新包暂存为 `.pending`。重启助手等待原进程退出，在目标目录准备完整文件，再进行原子替换。旧进程未退出或文件系统拒绝原子替换时保留原包。
8. 更新期间可以取消下载。取消只清理临时文件，不会删除或覆盖当前可用版本。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `XIANYU_ASSISTANT_UPDATE_CHECK_ENABLED` | `true` | 是否检查在线更新 |
| `XIANYU_ASSISTANT_UPDATE_GITHUB_REPOSITORY` | `yuchen0x1/Xianyu-Assistant` | Release 仓库，格式为 `owner/repository` |
| `XIANYU_ASSISTANT_UPDATE_CACHE_SECONDS` | `300` | 检查结果缓存秒数，`0` 表示关闭缓存 |
| `XIANYU_ASSISTANT_UPDATE_CHANNEL` | `stable` | 更新通道：`stable` 稳定版、`beta` 仅预发布版、`dev` 最新稳定版或预发布版 |
| `XIANYU_ASSISTANT_SELF_UPDATE_ENABLED` | `true` | 是否允许应用内替换 JAR |
| `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE` | `exit` | `exit`、`self` 或 `manual` |
| `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED` | `true` | 是否定时自动更新 |
| `XIANYU_ASSISTANT_AUTO_UPDATE_CHECK_INTERVAL_MS` | `21600000` | 自动检查间隔，默认 6 小时 |
| `XIANYU_ASSISTANT_AUTO_UPDATE_INITIAL_DELAY_MS` | `300000` | 启动后首次检查延迟，默认 5 分钟 |
| `XIANYU_ASSISTANT_SELF_UPDATE_SIGNATURE_REQUIRED` | `auto` | `auto` 对官方仓库强制验签，或显式设为 `true` / `false` |
| `XIANYU_ASSISTANT_RELEASE_PUBLIC_KEY` | 内置官方公钥 | 社区 Fork 自行轮换 Ed25519 公钥时使用 |
| `XIANYU_ASSISTANT_UPDATE_JAR_BASE_URL` | 空 | 可选镜像前缀；内容仍必须通过官方 SHA-256 校验 |

生产环境建议保留默认签名强制策略，并把 `dbdata/` 单独备份。使用独立 JAR 的 `exit` 模式时，确认 systemd 设置 `Restart=always` 或其他进程管理器具备等效配置。

`beta` 和 `dev` 通道通过 GitHub Releases 列表筛选 Release：`beta` 只选择 GitHub 标记为预发布的版本，`dev` 选择最新的稳定版或预发布版。预发布标签（例如 `v3.6.0-beta.2`）会保留在版本比较和下载资产定位中。

## 发布新版本

版本号必须同时一致：

- `pom.xml`
- `vue-code/package.json` 与 `package-lock.json`
- Git 标签 `vMAJOR.MINOR.PATCH`

`release.yml` 使用 `main` 已有的 `v1.2.3` 标签，复用 CI。经过 Linux、Windows、macOS Intel 和 Apple Silicon 启动检查的同一份 JAR 进入发布步骤。CI 对 JAR 和 `update-manifest.json` 分别生成 Ed25519 签名，在公开前比对草稿中每份资产的大小和 SHA-256。正式版受 GitHub Release 不可变设置保护。更新器沿用 GitHub Releases API 和 JAR 独立签名，签名清单供部署工具离线核验。

需要在仓库 Secrets 中配置：

- `RELEASE_SIGN_PRIVATE_KEY`：PKCS#8 DER Base64 格式的 Ed25519 私钥。
- `GITHUB_TOKEN` 不需要手工创建，工作流使用 GitHub 自动提供的令牌，并只授予 `contents: write`。

本地发布脚本：

```bash
RELEASE_SIGN_PRIVATE_KEY='...' \
GITHUB_TOKEN='...' \
bash scripts/publish-github.sh 3.5.29
```

从已有远程标签的同一提交运行脚本。失败留在草稿时，可从原提交重跑。已公开的版本会被拒绝重复发布。

## 故障处理

- 检查失败：应用继续正常运行，前端显示“无法确认最新版本”。
- 下载失败、资产缺失、大小不一致、SHA-256 不匹配或验签失败：拒绝替换并保留当前 JAR。
- 磁盘空间不足：更新不会开始，清理空间后重新检查即可。
- 重启失败：`manual` 模式保留 `SWAPPED` 状态，`exit` 模式交给外部进程管理器。前端健康检查超时后，停服务并按部署指南选择 `.bak` 人工回退。恢复数据库之前需评估迁移和升级后的数据写入。
- 启动时发现当前 JAR 缺失但 `.bak` 存在：服务会先尝试从备份恢复。

## 验证清单

本地可执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
.\mvnw.cmd --batch-mode --no-transfer-progress test
Set-Location vue-code
npm test
npm run type-check
npm run build
```

真实 GitHub Release 下载、签名密钥、代码签名证书、systemd/Docker 重启和外部网络故障演练需要在发布环境中验证；本地测试不会伪造这些外部结果。

## 当前限制

- GitHub 私有仓库需要额外的受控认证配置；默认客户端只访问公开 Release API。
- 本项目仅提供网页版；JAR 的 Ed25519 签名用于服务端发布包验证，不涉及原生客户端安装包或应用商店更新。
- GitHub Release 的强制更新和安全修复标记使用发布说明中的 `<!-- mandatory -->` 与 `<!-- security-fix -->` 标记；未标记时按可选更新处理。
