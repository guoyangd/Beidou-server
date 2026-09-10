#!/bin/sh
# 手动立即备份一次：起临时 beidou-backup 容器（单次模式），完成后自动销毁
set -e
cd "$(dirname "$0")"
docker compose run --rm -e BACKUP_INTERVAL=0 beidou-backup
