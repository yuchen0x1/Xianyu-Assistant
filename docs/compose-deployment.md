# Docker Compose 部署

在带 Docker Engine 和 Compose v2 的 Linux 主机上，以 GitHub 上选定的版本源码构建镜像。运行时使用 Java 21、Playwright Chromium 和独立的 SQLite 数据卷，主机端口默认只绑定 `127.0.0.1`。手机和其他电脑通过你管理的 HTTPS 反向代理访问。

```bash
XIANYU_IMAGE_TAG=3.5.29 docker compose build
XIANYU_IMAGE_TAG=3.5.29 docker compose up -d --wait
docker compose logs --tail 100
```

`XIANYU_HOST_PORT` 可覆盖默认主机端口 `12400`。应用健康端点为 `/actuator/health`，版本端点为 `/api/system/version`。首次启动后在网页中创建管理员。相同的 Compose 项目名会复用 `data` 和 `logs` 卷。

## 复用已有目录

已有 `docker run` 或旧 Compose 用户先停止服务并备份整个数据目录。创建本地 `compose.override.yaml`，把 `source` 设为原来挂载的绝对路径。该目录必须对镜像内的 `pwuser` 可写。

```yaml
services:
  console:
    volumes:
      - type: bind
        source: /absolute/path/to/existing/dbdata
        target: /app/dbdata
```

用 `docker compose config` 确认数据路径，再启动并检查原有账号和配置。备份需包含 SQLite、密钥、知识库和其他数据文件。应用在线导出可用于备份业务数据，完整目录复制应在服务停止后进行。

## 升级与回退

1. 查看正式 Release 的升级说明，停止服务并备份数据卷。
2. 在目标版本源码目录使用新的 `XIANYU_IMAGE_TAG` 构建镜像并执行 `docker compose up -d --wait`。
3. 核对 `/api/system/version`，登录后确认账号和配置可用。
4. 服务启动异常时，使用上一版本源码和先前保留的镜像标签，运行 `docker compose up -d --no-build --wait`。若新版运行过数据库迁移，依据版本说明判断是否需要在停机后单独恢复备份。

Compose 镜像默认关闭容器内 JAR 替换和自动安装。控制台仍可检查 Release。保存旧镜像标签有助于回退。普通维护使用 `docker compose down` 保留数据卷。
