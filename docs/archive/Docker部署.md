# Docker 部署指南

一套 `Dockerfile` + `docker-compose.yml`（均在仓库根目录）即可在任意有 Docker 的机器上部署北斗服务端，自带 MySQL 8.4，首次启动自动建库并执行 Flyway 迁移。

## 快速开始

```bash
# 0. 国内网络先设镜像源（docker.io 被墙/DNS 污染时必须；海外机器跳过）
export IMAGE_PREFIX=docker.m.daocloud.io/library/
export MYSQL_IMAGE=docker.m.daocloud.io/library/mysql:8.4

# 1. 按需设置客户端可達的对外地址（公网 IP 或域名），否则客户端连不上频道服
export WAN_HOST=1.2.3.4          # 可选：MYSQL_ROOT_PASSWORD（默认 root）、GMS_SERVICE_LANGUAGE（默认 zh-CN）

# 2. 构建并启动（首次构建需下载 Maven 依赖 + 600MB wz，耗时较长）
docker compose up -d --build

# 3. 观察启动日志，看到「北斗 启动完成」即成功
docker compose logs -f server
```

可用镜像源（任选其一，均为 docker.io 透明代理）：`docker.m.daocloud.io`、`docker.1ms.run`、`docker.xuanyuan.me`。
也可不改 compose 变量，直接在 Docker Desktop 的 daemon.json 配 `registry-mirrors` 全局生效。

## 端口说明

| 端口 | 用途 |
|---|---|
| 8686 | REST 管理接口（JWT 鉴权）；若构建时打包了 gms-ui 则同时托管管理后台 |
| 8484 | 游戏登录服（客户端连接） |
| 7575-7577 | 频道服（客户端连接） |

频道端口公式：`7575 + (channel-1) + world*100`。默认 1 大区 × 3 频道（`game_config` 表 `world.0.channel_size=3`）。扩容示例：

- 1 大区开 5 频道 → `docker-compose.yml` 端口段改 `7575-7579`，Dockerfile `EXPOSE` 同步调整
- 开第 2 大区（world 1）× 3 频道 → 需放开 `7675-7677`（跨大区端口段不连续，需逐段映射）

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | root | MySQL root 密码（db 与 server 两服务共用） |
| `WAN_HOST` | 127.0.0.1 | **客户端连接用的公网地址**，部署到其他机器必改 |
| `LAN_HOST` | 127.0.0.1 | 局域网地址 |
| `GMS_SERVICE_LANGUAGE` | zh-CN | `zh-CN` / `en-US` |
| `IMAGE_PREFIX` | （空） | docker.io 镜像源前缀，如 `docker.m.daocloud.io/library/` |
| `MYSQL_IMAGE` | mysql:8.4 | MySQL 镜像名，走镜像源时配套修改 |

数据源通过 `JAVA_TOOL_OPTIONS` 里的 `-Dmybatis-flex.datasource.mysql.*` 系统属性注入（`initDb` 与 MyBatis-Flex 均第一优先级读取），指向 compose 内的 `db` 服务。JVM 内存限制同样追加到该属性串（如 `-Xmx2g`）。

## 数据持久化

- MySQL 数据在具名卷 `db-data`，`docker compose down` 不丢失；`docker compose down -v` 才会删除（慎用）。
- wz/脚本/代码打进镜像，升级时 `git pull && docker compose up -d --build` 重建即可；wz 层有缓存，只有 wz 变更才重传。

## 可选：内嵌管理后台（gms-ui）

管理后台默认不打进镜像（`gms-server/src/main/resources/static/` 在 .gitignore 中）。需要同源托管时，构建镜像前执行：

```bash
cd gms-ui && yarn install && yarn build
mkdir -p ../gms-server/src/main/resources/static
cp -r dist/* ../gms-server/src/main/resources/static/
cd .. && docker compose up -d --build
```

之后通过 `http://<主机>:8686` 访问。REST 接口本身的 CORS 白名单在 `CorsConfig` 中（默认放行 `http://localhost:8787` 开发地址），若从其他域名以浏览器方式调用需调整。

## 常见问题

- **客户端登录后连不上频道**：`WAN_HOST` 没设或仍是 127.0.0.1，客户端拿到了回环地址。
- **server 容器反复重启（Connection refused）**：MySQL 首次初始化数据目录期间健康检查可能提前放行，服务端首发连接会被拒——`restart: unless-stopped` 会在 MySQL 就绪后自动恢复；若持续失败看 `docker compose logs server`。
- **不要用 `MYBATIS_FLEX_DATASOURCE_MYSQL_URL` 环境变量**：`ServerApplication.getStartParam` 生成的是小写下划线名，Linux 下 `System.getenv` 区分大小写读不到，会回落到 yml 默认的 localhost（macOS 本地开发因环境变量不区分大小写而发现不了）。数据源覆盖请走 `-D` 系统属性或 `--` 启动参数。
- **拉取基础镜像超时/被重置**：docker.io 被墙的典型症状（`auth.docker.io` 解析到 199.59.x.x），按上文设置 `IMAGE_PREFIX`/`MYSQL_IMAGE` 走镜像源。
- **首次启动较慢**：Flyway 全量迁移（99 个）+ wz 预载耗时属正常现象。
- **Swagger**：默认关闭（生产要求）。需要时按 Spring Boot 环境变量约定打开，如 `SPRINGDOC_APIDOCS_ENABLED=true`、`SPRINGDOC_SWAGGERUI_ENABLED=true`，并务必只在内网使用（`swagger` 可越权，见 CLAUDE.md 安全说明）。
- **内存**：默认 JVM 堆为容器内存的 1/4；小内存机器可通过 `JAVA_TOOL_OPTIONS` 显式限制，同时给 MySQL 加 `--innodb-buffer-pool-size` 限制。
