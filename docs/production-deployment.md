> 服务器地址、SSH 用户与端口属于部署机密，不写入公共仓库；
> 以下使用占位符，实际值以服务器配置为准。

# 生产部署说明

## 固定环境

| 项目         | 值                                                                     |
| ------------ | ---------------------------------------------------------------------- |
| 生产入口     | `http://127.0.0.1:8080`                                            |
| SSH 用户     | `<部署用户>`                                                                 |
| 自动部署 SSH | `<部署端口>`，仅允许密钥登录                                                  |
| systemd 服务 | `xianyu-assistant.service`                                             |
| 应用监听     | `127.0.0.1:12400`                                                      |
| 反向代理     | Nginx `:8080` -> `127.0.0.1:12400`                                     |
| 应用 JAR     | `/opt/xianyu-assistant/app.jar`                                        |
| 数据目录     | `/opt/xianyu-assistant/data/dbdata`                                    |
| 发布备份     | `/opt/xianyu-assistant/backups/releases`                               |
| 部署 Runner  | `xianyu-production`                                                    |
| Runner 服务  | `actions.runner.yuchen0x1-xianyu-delivery-assistant.xianyu-production.service` |
| 部署入口     | `/usr/local/sbin/deploy-xianyu-assistant`                              |

仓库不保存服务器密码或私钥。人工维护使用专用 SSH 密钥；自动部署由生产服务器上的自托管 Runner 主动连接 GitHub，不依赖 GitHub 托管 Runner 入站连接服务器。服务器固定使用 Java 21 和 Node.js 22.12.0 构建。

## 自动部署

推送到 `codex/stability-upgrade` 后，`Deploy production` 工作流会依次执行：

1. `xianyu-production` Runner 检出触发提交。
2. 前端安装锁定依赖，执行测试、类型检查和生产构建。
3. 后端执行完整测试并打包 JAR。
4. 使用 SQLite 在线备份 API 备份数据库，并保存旧 JAR。
5. 原子替换 JAR，重启 `xianyu-assistant.service`。
6. 检查应用和 Nginx 健康端点；应用健康检查失败时自动恢复旧 JAR。

Runner 以独立的 `github-runner` 系统用户运行。它只能通过 sudo 调用固定部署脚本；脚本校验版本号、提交 SHA、JAR 路径与文件所有者，不能执行任意 root 命令。Maven 和 npm 缓存保留在 Runner 用户目录，首次构建后会复用缓存。

原有 `XIANYU_DEPLOY_*` Secrets 不再参与自动部署，可以保留作应急切换记录；工作流本身不读取这些 Secrets。

## 人工复核

每次自动部署完成后至少确认：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsSI http://127.0.0.1:8080/
```

服务器侧排查命令：

```bash
systemctl status xianyu-assistant --no-pager
journalctl -u xianyu-assistant -n 200 --no-pager
cat /opt/xianyu-assistant/DEPLOYED_VERSION
cat /opt/xianyu-assistant/DEPLOYED_COMMIT
```

## 回退

工作流会为每次发布创建独立备份目录。需要人工回退时，选择目标备份目录中的 `app.jar`，覆盖当前 JAR 后重启服务。数据库是否回退必须单独判断；恢复数据库会覆盖部署后的业务数据，禁止跟随 JAR 自动回退。
