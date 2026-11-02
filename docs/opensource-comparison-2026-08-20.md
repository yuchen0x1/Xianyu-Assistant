# 开源同类项目对比（2026-08-20）

本次对比以 2026 年 8 月 20 日 GitHub 公开仓库状态为准，重点参考：

- `23Star/xianyu-super-butler`：在 `zhinianboke/xianyu-auto-reply` 基础上持续维护，重点覆盖账号监听、商品同步、订单处理、发货规则和现代化管理端。
- `zhinianboke/xianyu-auto-reply`：FastAPI + React + MySQL + Redis + Playwright，覆盖多账号、自动回复、自动发货、商品发布、自动评价和自动擦亮。
- `dameng2026/xianyu-pilot`：FastAPI + Vue 3 + Playwright + MySQL + Redis，强调定时任务、数据看板、RAG、商品发布和 Docker Compose 部署。
- `shaxiu/XianyuAutoAgent`：强调上下文感知、多专家路由和智能议价。

## 我们已经具备

- Java 21 + Spring Boot 3.5 的单体控制台、Vue 3 管理端和 Docker/JAR 部署。
- 多账号、WebSocket 消息、Cookie/Token 保活、关键词回复、AI/RAG 知识库、人工接管。
- 文本/图片/卡密发货、库存预占与回滚、结果未知核对、批量确认发货。
- 商品同步、SKU、发布/下架、价格历史、批量自动化配置。
- 持久化任务引擎、风险拦截、代理池、Webhook、RBAC、备份恢复、Prometheus 指标和多渠道通知。

## 差距与处理结论

| 能力 | 同类项目常见做法 | 当前状态 | 本次处理 |
| --- | --- | --- | --- |
| 自动评价/求好评 | 订单完成后延迟任务 + 模板 + 黑白名单 | 缺少闲鱼评价写接口验证 | 暂不直接实现，避免未经验证的写操作触发风控 |
| 智能议价 | LLM 意图识别 + 最低价/轮数限制 | 当前 AI 回复没有议价策略字段 | 纳入下一阶段，先设计策略模型和模拟测试 |
| 商品发布计划 | 素材库、定时发布、定时下架、失败补偿 | 已有单次发布/下架和历史 | 后续接入现有任务引擎，不复制新的调度系统 |
| 库存/发货补偿 | 失败重试、未知结果人工核对、低库存通知 | 已具备 | 保持现有实现，继续补监控与告警测试 |
| 运行可观测性 | 健康检查、任务积压、失败率、通知 | 已具备基础指标和通知 | 后续增加任务级聚合报表 |
| 依赖与运行时 | Playwright、SQLite、MsgPack 等持续跟进 | 部分依赖落后 | 本次更新到稳定版本并执行全量测试 |

## 依赖更新

本次更新 `pom.xml` 中的兼容性较高依赖：Playwright `1.52.0 -> 1.62.0`、SQLite JDBC `3.49.1.0 -> 3.53.2.1`、MsgPack `0.9.8 -> 0.9.12`、Gson `2.13.2 -> 2.14.0`、MyBatis-Plus `3.5.5 -> 3.5.17`、Java-WebSocket `1.5.4 -> 1.6.0`、JUnit Jupiter `5.10.0 -> 5.14.4`。

暂不升级 Spring Boot 4、Spring AI 2、TypeScript 7、OkHttp 5、Java 22 等跨代版本；它们需要独立分支、迁移说明和更长的回归窗口。

## 参考

- https://github.com/23Star/xianyu-super-butler
- https://github.com/zhinianboke/xianyu-auto-reply
- https://github.com/dameng2026/xianyu-pilot
- https://github.com/shaxiu/XianyuAutoAgent
