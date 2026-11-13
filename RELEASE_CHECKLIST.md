# 发布前检查清单（Release Checklist）

> 适用项目：闲鱼卖家运营控制台（Xianyu Assistant / XianYu Seller Console）
> 发布入口：`.github/workflows/release.yml`（push tag 自动构建并发布）

## 一、版本一致性

- [ ] Web 前端与服务端版本号已对齐（`pom.xml`、`vue-code/package.json` 及版本回退值）
- [ ] 版本号符合现有 MAJOR.MINOR.PATCH 惯例，与上一个发布版本（`git tag` 最新）不冲突
- [ ] 无未提交的工作区改动（`git status` 干净）

## 二、更新内容整理

- [ ] `git log v上一版本..HEAD` 全部提交已归类（功能 / 优化 / 修复 / 安全 / 文档 / 开发者）
- [ ] CHANGELOG.md 新版本条目完整、无遗漏用户可见变化，历史版本记录未删除
- [ ] RELEASE_NOTES_CN.md 与 RELEASE_NOTES_EN.md 内容一致
- [ ] README 配置参考与新增/变更环境变量同步

## 三、质量验证

- [ ] 后端全量测试通过（`./mvnw test`）
- [ ] 前端类型检查与构建通过（`cd vue-code && npm ci && npm run build`）
- [ ] 本地打包成功（`./mvnw -B -ntp -DskipTests package`）且 `target/*.jar` 大小正常（约 287 MB）
- [ ] 前端构建产物已包含在 JAR 中（`src/main/resources/static/`）

## 四、发布与产物

- [ ] Tag 已创建并推送（格式 `vMAJOR.MINOR.PATCH`），指向最终 release commit
- [ ] GitHub Actions `release.yml` 构建成功
- [ ] 产品仅包含网页版；仓库、构建流程与附件没有独立移动端或桌面客户端
- [ ] Release 资产齐全：`XianYuSellerConsole-<版本>.jar`、`.jar.sha256`、`.jar.sig`
- [ ] JAR 本地 SHA-256 与 `.sha256` 文件一致（`sha256sum -c`）
- [ ] `.jar.sig` Ed25519 验签通过（CI 内置公钥）
- [ ] Release Notes（CN/EN）已填入 GitHub Release 正文

## 五、升级链路

- [ ] 上一版本实例可正常在线更新到本版本（验签、原子替换、重启）
- [ ] systemd `Restart=always` / `restart-mode` 行为在文档中说明
- [ ] SchemaMigrator 数据库迁移随版本号正确递增（如新增）
- [ ] 发布签名私钥在 CI Secret 中可用（`RELEASE_SIGN_PRIVATE_KEY`）；如发生密钥轮换，已在 Release 说明中提示用户重新下载

## 六、发布后

- [ ] Release 页面资产与说明核对无误
- [ ] 应用内「检查更新」能发现新版本
- [ ] 自动更新调度器（`SelfUpdateAutoScheduler`）在测试实例上完成一次完整升级
- [ ] 安装脚本 `install.sh` 可从 GitHub Release 正常下载并校验通过
