# 贡献指南

在 `main` 上创建 `feature/*`、`fix/*` 或 `codex/*` 分支，通过 PR 合并。出现冲突时先将 `main` 合并到工作分支，解决冲突后重跑检查。维护者依据 PR 的影响说明和检查结果决定合并。紧急修复沿用同样流程，从 `main` 发布新的补丁标签。

- 使用 Java 21、Node.js 22.12+、Maven Wrapper 和 `npm ci`。开发入口见 [README](README.md#开发环境与验证)。
- 后端运行 `./mvnw test`（Windows 用 `mvnw.cmd`）。前端在 `vue-code/` 下执行 `npm test`、`npm run lint:check` 和 `npm run build`。
- 修改 API、SQLite、权限、发布或更新时，在 PR 说明旧数据和旧版本的处理方式，记录验证结果。
- 提交截图与日志前移除凭据、卡密、可识别账号信息及订单数据。安全问题请使用 [私密报告](SECURITY.md)。
- 维护者从 `main` 上已通过检查的提交创建 `vMAJOR.MINOR.PATCH` 并推送。流程和回退条件见 [发布检查清单](RELEASE_CHECKLIST.md)。

讨论应针对代码和使用场景。请尊重其他贡献者和用户隐私。骚扰、歧视、威胁和公开传播他人隐私会由维护者依据 GitHub 社区规范处理。
