# 闲鱼卖家运营控制台 v3.5.20

> Release: 2026-11-08

本版本是一个**运营效率版**：新增待办中心，把卖家每天最高频的四类事务集中到一个入口；商品管理补齐批量下架与批量改价；移动端从纯 WebView 壳升级为带原生工作台的客户端。

## ✨ 版本亮点

- 📋 **待办中心**：一个页面汇总待发货、待回复、发货待复核、自动评价待确认四类待办，按账号权限过滤，点击直达对应处理页。
- 📦 **商品批量下架**：商品列表多选后一键下架，单批最多 50 件，逐件返回成功/失败明细。
- 💰 **商品批量改价**：选中商品统一设置新售价，金额规则与单件编辑一致，逐件失败隔离，不触碰标题与详情。
- 📱 **移动端原生工作台**：Flutter 首页提供服务健康状态、连接配置与四个高频快捷入口，复杂配置一键进入完整控制台。

## 新功能

### 📋 待办中心

- 新增聚合接口 `GET /api/todo/overview`：按当前用户的 RBAC 账号范围汇总四类待办——待发货订单、待回复会话、发货待复核记录、自动评价待确认记录；单个数据源失败不影响其他类别。
- Web 控制台新增「待办中心」页面（工作台分组首位入口）：四类统计卡片、分类 Tab、自动刷新、时间格式化，点击任一条目直达 `待发货` / `消息` / `订单` / `评价` 对应页面处理。
- 移动端首页提供待办中心入口，复用完整控制台内的登录态，不在原生层保存任何凭据。

### 📦 商品批量下架

- 新增 `POST /api/publish/batchOffShelf`：单批上限 50 件、逐件间隔 1 秒规避平台频控、独立限流桶（3 次/分钟）、逐件成功/失败明细，单件失败不中断批次。
- 商品列表新增多选：仅在售商品可选，桌面表格与移动卡片均支持勾选与表头全选（含半选态）；切换账号、状态筛选或翻页时自动清空选择，避免跨账号/跨页误操作。
- 操作栏显示已选数量，下架为危险操作需二次确认。

### 💰 商品批量改价

- 新增 `POST /api/items/batchUpdatePrice`：将选中商品统一更新为新售价，`goods:write` 权限 + RBAC 账号校验 + 独立限流桶，逐件返回成功/失败明细。
- 底层新增专用 `updateItemPrice`：只修改售价与更新时间，不覆盖标题、详情、图片等其他资料；金额规则沿用既有校验（大于 0、最多两位小数、上限 1 亿）。
- **明确边界**：该能力只更新助手本地商品资料，界面与接口消息均提示「闲鱼远程商品请在官方页面确认」，不伪装远程改价成功。

### 📱 移动端原生工作台

- Flutter 首页从纯 WebView 壳升级为原生工作台：服务在线状态与版本展示、服务地址配置与持久化、下拉刷新健康探测（`/api/system/health`）、待办中心入口、待发货/待回复/待复核/待评价四个快捷入口、「打开完整控制台」按钮。
- 完整控制台支持按入口深链加载指定业务路由；WebView 导航白名单保持严格限制在配置服务的 scheme/host/port。
- 原生层不存储登录 Token，认证仍由 WebView 内的控制台会话安全维护。

## 升级影响

- 无破坏性配置变更，无数据库迁移。
- 新接口沿用既有权限体系：待办中心需 `dashboard:read`，批量下架需 `publish:write`，批量改价需 `goods:write`。
- 批量改价只写本地资料，升级后无需重新同步商品。

## 下载与安装

| 平台 | 文件名称 | 架构 | 说明 |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.20.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar) | x86_64 / arm64 | 完整服务端 JAR（约 287 MB），已内置前端页面 |
| 校验文件 | [XianYuSellerConsole-3.5.20.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sha256) | — | SHA-256 校验值 |
| 签名文件 | [XianYuSellerConsole-3.5.20.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sig) | — | Ed25519 官方签名 |

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

**从 v3.5.19 升级**

- **推荐：应用内在线更新或自动更新**，升级过程与既往版本一致。
- 升级前建议照常备份 `dbdata/` 目录。
- `exit` 重启模式（默认）需进程管理器配合：systemd 单元请确认已配置 `Restart=always`。

## 系统要求

- **Java 21**（必需；`install.sh` 可自动安装）
- **Docker 20.10+**（仅容器部署需要）
- SQLite 为内置存储，无需外部数据库
- 默认端口 `12400`，浏览器访问 Web 控制台

## 更新日志

完整提交对比：[v3.5.19...v3.5.20](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.19...v3.5.20)

**验证情况**：新增 `TodoServiceImplTest`、`PublishServiceImplBatchOffShelfTest`、`ItemServiceImplBatchUpdatePriceTest` 三组回归测试；后端全量 662 项测试通过（0 失败 / 0 错误 / 3 跳过）；前端类型检查与 34 项 Vitest、Flutter `analyze`/`test`、Android debug APK 构建全部通过。
