# 历史方案归档

本目录是早期「仓库根目录 Dockerfile + docker-compose.yml」部署方案的手册与脚本，
该方案已被仓库根的 [`docker/`](../../docker/README.md) 目录方案取代（生产在用后者）。

保留这些文件仅作参考：`IMAGE_PREFIX` 镜像前缀构建、wz 先拷贝利用层缓存等思路
已吸收进 `docker/Dockerfile`。

| 文件 | 内容 |
|---|---|
| Docker部署.md | 旧方案部署手册 |
| 家用服务器docker-compose.yml | 旧方案在家用服务器/NAS 上的 compose 样例 |
| 打包部署手册.md | 旧方案镜像打包流程 |
| backup.sh | 旧方案配套的数据库备份脚本（新版见 `docker/backup-entrypoint.sh`） |
