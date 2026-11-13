# 闲鱼卖家运营控制台 Web 前端

本目录是项目唯一的用户界面，电脑和手机均通过浏览器使用。完整功能、服务端部署与升级说明见[项目 README](../README.md)。

## 环境与依赖

- Node.js 20.19+ 或 22.12+（以 `package.json` 的 engines 为准）。
- Vue 3、TypeScript、Vue Router、Axios、Vite；页面使用项目自己的样式与组件。
- 使用 `npm ci` 按已提交的 `package-lock.json` 安装依赖，无需额外安装 Element Plus 或 Pinia。

## 开发

先启动 Java 服务端（默认 `http://127.0.0.1:12400`），再从项目根目录执行：

```bash
cd vue-code
npm ci
npm run dev
```

开发页面默认地址为 `http://localhost:5173`。`/api` 和 `/ai` 由 Vite 代理到后端；可通过 `VITE_API_PROXY_TARGET` 指定后端地址。生产环境由 Spring Boot 提供静态页面和接口，不依赖 Vite 开发代理。

Windows 也可使用根目录的 `scripts/start-dev.ps1` 同时运行前后端，具体前置构建和端口说明见项目 README。

## 验证与构建

```bash
npm test
npm run lint:check
npm run type-check
npm run build
```

`npm run build` 包含类型检查，并直接把页面输出到 `../src/main/resources/static/`；无需从 `dist` 手工复制。Vite 会清理旧输出，生产构建不生成 source map。

在项目根目录继续执行 `./mvnw -B -ntp clean package`（Windows 使用 `mvnw.cmd`），即可将页面与后端一起打包。Docker 和正式发布流水线会自动完成这两个步骤。

## 目录

| 路径 | 用途 |
| --- | --- |
| `src/api/` | 业务 API |
| `src/utils/request.ts` | 请求、认证与错误处理 |
| `src/router/` | 页面路由与权限守卫 |
| `src/views/` | 业务页面 |
| `src/components/` | 布局与共用组件 |
| `src/styles/` | 主题、基础样式与设计令牌 |
| `public/` | 页面实际使用的静态资源 |
| `tests/` | Vitest 测试 |

`node_modules/`、构建产物、缓存、日志和本地环境文件不提交；数据库与密钥由服务端保存在忽略的运行目录中。
