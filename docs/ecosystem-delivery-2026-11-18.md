# 2026-11-18 项目生态交付报告

## 交付范围

- 工作分支：`codex/project-ecosystem`
- 当前版本：`3.5.28`
- 提交与评审：GitHub PR #35
- 产品形态：响应式 Web 控制台、独立 JAR、Docker Compose。当前没有独立 Android、iOS 或桌面客户端。

本批次围绕仓库基础设施、持续集成、发布物完整性、更新可靠性、部署边界、平台兼容性和竞品证据进行了收敛。业务数据、`dbdata/`、Cookie、密钥和真实闲鱼账号均未读取或改写。

## 已完成

### 工程与发布

- 版本统一到 `3.5.28`，同步 Maven、前端包清单、运行时回退值、更新界面和发布文档。
- Release 工作流复用主 CI，发布前使用同一份 JAR 通过 Linux、Windows、macOS Intel 和 Apple Silicon 启动检查。
- 发布脚本改为草稿发布，逐一核验 JAR、SHA-256、Ed25519 签名、更新清单和清单签名的大小与摘要，再公开 Release。
- 启用 GitHub 不可变 Release，增加 `main` 分支规则集、必需状态检查、依赖审查和 CodeQL 默认配置。
- 增加 CODEOWNERS、Issue 模板、PR 模板、许可证审阅规则和维护工作流。

### 部署与更新

- 增加 Docker Compose 入口、命名数据卷、日志卷、健康检查和 Compose 部署说明。
- Docker 默认关闭应用内 JAR 替换，升级路径为构建新镜像、健康检查、保留卷并在失败时回退镜像。
- 独立 JAR 更新前核对包内 `Implementation-Version`，Windows 换包流程等待旧进程退出并采用临时文件和原子替换。
- 增加维护工作流，可从既有 Release 下载签名基线，执行发现更新、下载、替换、重启、SQLite 登录持久化和旧包备份检查。
- 生产部署工作流限制为 `main`、专用 Runner 和显式仓库变量 `PRODUCTION_DEPLOY_ENABLED=true`。

### 研究与平台验证

- 新增 2026-11-18 竞品与技术参考快照，包含 31 个公开仓库、许可证、活跃度、源码目录、Issue 样本和采用判断。
- 保留单一 Web 服务覆盖桌面与手机浏览器。已使用 BrowserSkill 验证桌面和 390px 移动视口的登录后控制台布局。
- 增加平台矩阵、Compose 部署、自动更新和生产部署文档的交叉入口。

## 已验证证据

| 检查 | 结果 |
| --- | --- |
| Maven 全量测试 | 713 个测试通过，0 失败，0 错误，3 跳过 |
| 前端测试 | 44 个测试通过 |
| 前端类型检查、Lint、Vite 构建 | 通过 |
| Windows JAR 启动烟测 | 通过，健康检查、版本接口和 Web 资源均可用 |
| 运行时依赖审计 | 0 个高危运行时漏洞 |
| 工作流 actionlint | 通过 |
| Docker Compose 本机启动 | 未验证，本机 Docker daemon 不可用 |
| GitHub Actions 新一轮 PR 检查 | 修复后等待 GitHub 重新执行 |

前端构建依赖仍报告 `picomatch` 的高危开发依赖公告，当前公告没有可用修复版本。它不进入运行时安装，并已由 CI 脚本单独记录为已知例外，后续升级 Playwright 或上游依赖时继续复查。

## 未宣称完成的范围

- 真实闲鱼账号登录、消息、订单、发货和风控结果仍需在用户授权的账号与网络环境中验证。
- 真实 Release `v3.5.28` 尚未创建，当前 PR 未合并，生产部署未执行。
- 独立 JAR 从已发布旧版本到当前版本的真实 GitHub Release 升级链路由维护工作流提供入口，尚待使用已发布资产运行。
- 本机没有 Docker daemon，因此 Compose 构建与卷重建依赖 GitHub Actions 结果。
