# BeiDou-Server 全自包含镜像：UI + 服务端 jar + wz + scripts
# 构建上下文为仓库根目录：docker build -f docker/Dockerfile .
# 国内网络拉不动 docker.io 时，给基础镜像加镜像源前缀构建：
#   docker compose build --build-arg IMAGE_PREFIX=docker.m.daocloud.io/library/
#   （compose 里已透传该 build arg，也可 export DOCKER_BUILDKIT=1 后直接用）
ARG IMAGE_PREFIX=""

#############################
# Stage 1: 构建 gms-ui 前端  #
#############################
# 注意：vite-plugin-imagemin 依赖 gifsicle/jpegtran/pngquant 等原生二进制，
# 必须用 Debian(glibc) 基础镜像才能直接用预编译包；alpine(musl) 会全部回退源码编译而失败
FROM ${IMAGE_PREFIX}node:20.15.0-bookworm-slim AS ui-builder

WORKDIR /build

# 先只拷依赖描述文件，利用层缓存加速重复构建
COPY gms-ui/package.json gms-ui/yarn.lock ./
RUN yarn install --frozen-lockfile --network-timeout 600000

# 再拷源码与构建配置（.dockerignore 已排除 node_modules/dist）
COPY gms-ui/ ./
RUN yarn build

##############################
# Stage 2: 构建服务端 jar     #
##############################
FROM ${IMAGE_PREFIX}maven:3.9.6-amazoncorretto-21 AS server-builder

WORKDIR /build

# 先拷 pom 利用层缓存预下载依赖
COPY pom.xml ./
COPY gms-server/pom.xml ./gms-server/pom.xml
RUN mvn -B dependency:resolve --no-transfer-progress

# 前端产物嵌入 jar（Spring Boot 同源托管，8686 同时提供 UI 与 REST）
COPY --from=ui-builder /build/dist ./gms-server/src/main/resources/static

# 服务端源码
COPY gms-server/src ./gms-server/src
RUN mvn -B package -DskipTests --no-transfer-progress

# 组装运行时目录：jar + 配置 + 双语 wz/scripts
RUN mkdir -p /result \
    && mv gms-server/target/BeiDou.jar /result/BeiDou.jar
COPY gms-server/src/main/resources/application.yml /result/application.yml
COPY gms-server/wz          /result/wz
COPY gms-server/wz-zh-CN    /result/wz-zh-CN
COPY gms-server/scripts     /result/scripts
COPY gms-server/scripts-zh-CN /result/scripts-zh-CN

####################################
# Stage 3: 运行时（JRE21 alpine）   #
####################################
FROM ${IMAGE_PREFIX}eclipse-temurin:21-jre-alpine

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -Duser.timezone=Asia/Shanghai"

COPY --from=server-builder /result /opt/server_backup
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 运行目录（首次启动由 entrypoint 从 /opt/server_backup 初始化）
VOLUME /opt/server
WORKDIR /opt/server

# 8686 管理后台(UI+REST) / 8484 登录服 / 7575-7577 频道
# 频道端口公式：7575 + (频道号-1) + 世界号*100
EXPOSE 8686 8484 7575 7576 7577

ENTRYPOINT ["/entrypoint.sh"]
