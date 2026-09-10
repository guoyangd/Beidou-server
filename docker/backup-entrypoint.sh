#!/bin/sh
# BeiDou 数据库备份脚本
# 定时模式（默认）：每天在 BACKUP_TIMES 指定的固定时间点备份（如 04:00,16:00）
# 手动模式：BACKUP_INTERVAL=0 时备份一次即退出
set -e
set -o pipefail

DB_HOST="${DB_HOST:-beidou-db}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
DB_NAME="${DB_NAME:-beidou}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_TIMES="${BACKUP_TIMES:-04:00,16:00}"
BACKUP_KEEP="${BACKUP_KEEP:-28}"

do_backup() {
    ts=$(date +%Y%m%d-%H%M%S)
    file="$BACKUP_DIR/beidou-${ts}.sql.gz"
    echo "[$(date '+%F %T')] dumping ${DB_NAME} -> $file"
    # --single-transaction: InnoDB 一致性快照，不锁表；MYSQL_PWD 避免命令行密码告警
    if ! MYSQL_PWD="$DB_PASSWORD" mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
        --single-transaction --routines --triggers --events --no-tablespaces \
        "$DB_NAME" | gzip > "$file"; then
        rm -f "$file"
        echo "[$(date '+%F %T')] backup FAILED, removed partial file"
        return 1
    fi
    # 滚动删除超出保留数量的最旧备份
    ls -1t "$BACKUP_DIR"/beidou-*.sql.gz 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)) | xargs -r rm -f
    echo "[$(date '+%F %T')] done: $file ($(du -h "$file" | cut -f1))"
}

# 距离 BACKUP_TIMES 中最近一个时间点还有多少秒（过了今天该点则算明天）
seconds_until_next() {
    now_secs=$((10#$(date +%H) * 3600 + 10#$(date +%M) * 60 + 10#$(date +%S)))
    best=-1
    for t in $(echo "$BACKUP_TIMES" | tr ',' ' '); do
        tt=$((10#${t%%:*} * 3600 + 10#${t#*:} * 60))
        d=$((tt - now_secs))
        [ "$d" -le 0 ] && d=$((d + 86400))
        if [ "$best" -lt 0 ] || [ "$d" -lt "$best" ]; then best=$d; fi
    done
    echo "$best"
}

mkdir -p "$BACKUP_DIR"

echo "Waiting for database server ${DB_HOST}:${DB_PORT} ..."
until MYSQL_PWD="$DB_PASSWORD" mysqladmin -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" ping >/dev/null 2>&1; do
    sleep 3
done

# beidou 库由服务端启动时创建（首次启动约晚几十秒），等它出现再备份
echo "Waiting for database '${DB_NAME}' to exist ..."
until MYSQL_PWD="$DB_PASSWORD" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
        -e "USE \`$DB_NAME\`;" >/dev/null 2>&1; do
    sleep 5
done

# 启动立即备份一次
do_backup

# 手动模式：备份一次即退出
if [ "${BACKUP_INTERVAL:-}" = "0" ]; then
    echo "Manual mode (BACKUP_INTERVAL=0), exiting."
    exit 0
fi

# 定时模式：每天在固定时间点执行（容器重启会重新计算下一个时间点，不漂移）
echo "Scheduled mode: backup daily at ${BACKUP_TIMES}, keeping ${BACKUP_KEEP} copies."
while true; do
    wait=$(seconds_until_next)
    echo "[$(date '+%F %T')] next backup in $((wait / 3600))h$((wait % 3600 / 60))m"
    sleep "$wait"
    do_backup
done
