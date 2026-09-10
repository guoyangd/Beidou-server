#!/bin/sh
# 恢复数据库备份：./restore.sh data/backups/beidou-xxxx.sql.gz
# 会先停 beidou-server、删除并重建 beidou 库再灌入备份，完成后重启服务端
set -e

FILE="$1"
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
    echo "用法: $0 <备份文件.sql.gz>"
    echo "示例: $0 data/backups/beidou-20260908-153000.sql.gz"
    exit 1
fi
case "$FILE" in
    /*) ;;
    *) FILE="$(pwd)/$FILE" ;;
esac

cd "$(dirname "$0")"

echo "==> 停止 beidou-server（恢复期间必须停服，防止写入交错）"
docker compose stop beidou-server

echo "==> 重建 beidou 库（DROP + CREATE）"
docker compose exec -T beidou-db sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS beidou; CREATE DATABASE beidou CHARACTER SET utf8mb4;"'

echo "==> 灌入备份: $FILE"
case "$FILE" in
    *.gz) gunzip -c "$FILE" | docker compose exec -T beidou-db sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" beidou' ;;
    *)    cat "$FILE"        | docker compose exec -T beidou-db sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" beidou' ;;
esac

echo "==> 重启 beidou-server"
docker compose start beidou-server

echo "恢复完成。"
