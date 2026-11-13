# 开源项目调研与整合记录

更新时间：2026-09-05

本项目只整合许可证清晰、与现有 Java/Vue Web 架构兼容的设计；没有复制许可证不明仓库、账号 Cookie、验证码处理或平台风控绕过代码。所有实际新增实现均由本项目维护者重新编写，并继续遵守本仓库 Apache-2.0 许可。

## 调研结论

| 项目 | 平台/方向 | 许可证核验 | 采用方式 |
| --- | --- | --- | --- |
| [DoLovya/xianyu-mcp-server](https://github.com/DoLovya/xianyu-mcp-server) | Python MCP、商品/会话工具 | GitHub API 返回 `NOASSERTION` | 仅参考 MCP 工具组织方式；未复制代码 |
| [fancyboi999/goofish-cli](https://github.com/fancyboi999/goofish-cli) | Goofish CLI/MCP | Apache-2.0 | 参考工具描述和受控调用边界；未复制代码 |
| [dameng2026/xianyu-pilot](https://github.com/dameng2026/xianyu-pilot) | 多账号、消息、发货、Docker | Apache-2.0 | 参考模块拆分和部署检查思路；未复制代码 |
| [IAMLZY2018/XianYuAssistant](https://github.com/IAMLZY2018/XianYuAssistant) | 本项目上游 | Apache-2.0 | 保留既有上游来源与版权说明 |
| [cv-cat/XianYuApis](https://github.com/cv-cat/XianYuApis) | 闲鱼 API 研究 | 未声明许可证 | 仅作为公开协议资料；不复制代码 |
| [ziling35/xianyu-auto](https://github.com/ziling35/xianyu-auto) | Python 自动回复 | 未声明许可证 | 不整合代码 |
| [zhinianboke/xianyu-auto-reply](https://github.com/zhinianboke/xianyu-auto-reply) | FastAPI + React 头部同类（6.9k star） | AGPL-3.0（传染性，禁商用） | 仅对比功能差距；不复制代码 |
| [23Star/xianyu-super-butler](https://github.com/23Star/xianyu-super-butler) | 同类二开重构（884 star） | AGPL-3.0 | 参考其"回复决策日志"设计思想；未复制代码 |
| [shaxiu/XianyuAutoAgent](https://github.com/shaxiu/XianyuAutoAgent) | 多专家 Agent 议价（9k star） | GPL-3.0 | 仅参考"意图分类→专家路由"架构思路；未复制代码 |
| [n8n](https://n8n.io) / [activepieces](https://www.activepieces.com) | 工作流编排 / Human-in-the-loop | fair-code / MIT | 仅参考"AI 不确定→挂起等人工审批"交互模式；未复制代码 |
| [uptime-kuma](https://github.com/louislam/uptime-kuma) | 告警交互（聚合/测试按钮） | GPL-3.0 | 仅作告警中心交互对照；已有能力基本覆盖 |

## 本次实际整合

参考公开 MCP 项目的工具化组织方式，本项目增强了 `POST /api/mcp`：

- 支持 JSON-RPC 批量请求，逐项返回结果；
- 空批次和批次中的非法元素返回标准错误码；
- 新增初始化、工具 schema、批量调用和异常路径回归测试；
- 保持既有认证拦截器，MCP 端点不会绕过登录和权限校验。

### 2026-09-05 整合：自动回复决策日志

参考 xianyu-super-butler 的"回复决策日志"（可追溯"为什么回了/为什么没回"），以本项目自有实现落地：

- 新增 `auto_reply_decision` 表与 `AutoReplyDecisionService`，只记录有追溯价值的跳过/失败事件（人工接管、商品未同步、无策略、未生成内容），开关关闭与消息去重等噪音不落库；
- 同会话同原因 60 分钟去重，写入旁路化（失败仅告警）；
- 自动回复页新增「未回复记录」查询弹窗。

同期还复核了 zhinianboke/xianyu-auto-reply 的商品采集/分销链路（与本产品"自营工具"定位不符，暂不引入）与上游 v2.0.4 的 WS→API 发货降级（本项目已有 `ApiDeliveryScheduler` 同等能力）。

本记录是归属和审计说明，不代表上述项目与本项目存在合作或官方兼容关系。
