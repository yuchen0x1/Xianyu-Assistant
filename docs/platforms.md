# 平台可行性与维护状态

服务端 Spring Boot 使用 Java 21、SQLite 和 Playwright，Vue 控制台通过同源 API 提供桌面与移动界面。消息连接、调度、库存和发货在服务端运行，浏览器关闭后仍可工作。支持范围由可复现的发行包、构建和验证决定。

| 平台 | 等级 | 可用性、代价与发布路径 |
| --- | --- | --- |
| 桌面 Web、响应式 Web | A | 完整控制台、键盘和文件操作。沿用同源 API、服务端 RBAC 与 SQLite。随 JAR/镜像分发，服务更新后刷新网页。 |
| 移动 Web | A | 延用同一服务，窄屏采用触控导航。服务器侧连接、自动化和凭据仍独立运行。Web 兼容性需逐浏览器验证。 |
| Windows 10 / 11 | A | Java 21 服务 JAR 加桌面浏览器。无内核驱动和管理员安装依赖，Playwright 可选流程受浏览器运行环境影响。维护统一 JAR；Windows 2022 Runner 验证进程启动与资源访问。 |
| Linux / Ubuntu | A | Java 21 JAR、`install.sh` 或 Compose。systemd/Nginx 可用于长期托管。Ubuntu 22.04 验证 JAR 与 Linux x64 镜像。 |
| Debian | B | 同一 JAR/Compose 适用具备 Java 21、SQLite JNI 与 Docker 的主机。浏览器依赖与 systemd 配置需实机验收。 |
| Fedora | B | 同一 JAR/Compose。需验证 SELinux 卷权限和浏览器运行依赖。 |
| Arch Linux | B | 同一 JAR/Compose。滚动更新造成浏览器与宿主库漂移，优先固定镜像或 Java 21。 |
| macOS Intel | B | Java 21 服务加浏览器，需兼容 Intel Playwright 和文件权限。JAR 由 `macos-15-intel` 运行验证。 |
| macOS Apple Silicon | B | Java 21 arm64 服务加浏览器，需对应架构的依赖。JAR 由 `macos-15` 运行验证。 |
| Docker | A | Linux 容器用 Compose 构建，固定数据卷和健康检查。选定源码版本重建镜像，可用原镜像标签回退。 |
| NAS | B | 具备 Linux x64 Docker 和 Compose 的型号可用相同容器。验证卷权限、持续磁盘写入和内存需按设备进行。其他架构待验证。 |
| 私有化部署、云服务器 | A | 同一 JAR 或容器，通过 TLS 反代、独立数据卷和访问控制维护。单实例 SQLite，扩容需重新设计数据与任务所有权。 |
| Android、Android 平板、折叠屏 | C | 浏览器可访问控制台。原生重复维护触控界面、更新和平台凭据，后台稳定性取决于设备。先覆盖 Web 实际使用需求。 |
| iOS、iPadOS | C | 浏览器可访问控制台。原生受后台执行限制，需签名和审核，产品主流程仍依赖常驻服务。 |
| PWA | C | 安装入口可行，消息、发货和库存依赖服务器。离线写入与缓存涉及私有数据及冲突合并，需要明确离线用例。 |
| Chrome、Edge 扩展 | C | 现有浏览器即可操作主要工作流。扩展需要独立商店审核和来源权限审核，账号凭据隔离与生命周期成本较高。 |
| Firefox 扩展 | C | 同样需要独立 WebExtension 兼容和审核，当前跨浏览器 Web 覆盖的收益更高。 |
| 原生桌面 MSI/DMG/AppImage/DEB/RPM/Flatpak | C | JAR 与浏览器完成主要操作。独立 GUI 需要窗口交互、安装签名、升级和卸载的长期维护。 |
| 独立 CLI | C | 项目已有服务安装脚本、同源 API 和授权命令入口。独立 CLI 需要稳定公开 API 与凭据存储方案。 |

## 功能与版本矩阵

| 功能 | 桌面 Web | 移动 Web | 运行位置 |
| --- | --- | --- | --- |
| 账号、商品、消息、订单、库存、设置 | 完整控制台 | 使用同一响应式路由，逐设备兼容性按验收记录 | Vue + Java API |
| 消息连接、自动回复、发货、计划任务、Webhook | 可配置并查看状态 | 可配置并查看状态 | Java 服务 |
| 文件导入、导出和备份管理 | 浏览器文件操作 | 受设备和浏览器文件选择能力影响 | 浏览器交互与服务端持久化 |
| 更新 | 后端版本提示与更新状态 | 同一后端版本与更新状态 | 独立 JAR 更新或重建容器 |

CI 状态以 [Actions](https://github.com/yuchen0x1/Xianyu-Assistant/actions) 为准。正式版本、JAR 和更新元数据以 [GitHub Releases](https://github.com/yuchen0x1/Xianyu-Assistant/releases/latest) 为准。源代码、服务端 JAR 和前端资源使用统一版本。上线后通过 `/api/system/version` 比对运行版本。

每轮维护在 PR 中记录受影响的平台、数据兼容性与验证结果。新功能优先使用现有 REST API、账号隔离与任务状态模型。项目发布和质量状态见 [本轮审计](../UPGRADE_REPORT.md)，功能候选见 [升级路线图](../UPGRADE_ROADMAP.md)。
