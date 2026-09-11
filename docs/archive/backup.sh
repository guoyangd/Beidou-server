#!/bin/bash
# BeiDou 数据库定时备份脚本（由 compose 的 beidou-db-backup 容器执行）
#
# 配置一律通过环境变量注入，均有默认值：
#   DB_HOST       MySQL 主机（默认 beidou-db）
#   DB_NAME       库名（默认 beidou）
#   DB_USER       用户（默认 root，密码走 MYSQL_PWD）
#   BACKUP_TIMES  每天备份时刻，空格分隔（默认 "01:00 18:30"）
#   KEEP_DAYS     保留天数（默认 14）
#   BACKUP_DIR    备份目录（默认 /backup）
#   ONCE=1        只备份一次就退出（用于手动触发/自测）
#
# 手动触发备份（无需命令行）：在 Container Station 界面重启 beidou-db-backup 容器即可，
# 脚本启动时会先立即做一次全量备份。
set -o pipefail

: "${DB_HOST:=beidou-db}"
: "${DB_NAME:=beidou}"
: "${DB_USER:=root}"
: "${BACKUP_TIMES:=01:00 18:30}"
: "${KEEP_DAYS:=14}"
: "${BACKUP_DIR:=/backup}"

backup() {
    local f="$BACKUP_DIR/beidou-$(date +%Y%m%d-%H%M%S).sql.gz"
    if mysqldump -h "$DB_HOST" -u"$DB_USER" --single-transaction --routines --triggers --events "$DB_NAME" | gzip > "$f"; then
        echo "[backup] 备份完成: $f ($(du -h "$f" | cut -f1))"
        find "$BACKUP_DIR" -name 'beidou-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
    else
        echo "[backup] 备份失败"; rm -f "$f"
    fi
}

# 计算今天剩余时刻里最近的一个；都过了则取明天第一个时刻
next_target() {
    local now=$1 t tt target=""
    for t in $BACKUP_TIMES; do
        tt=$(date -d "today $t" +%s)
        if [ "$tt" -gt "$now" ]; then target=$tt; break; fi
    done
    [ -n "$target" ] || target=$(date -d "tomorrow ${BACKUP_TIMES%% *}" +%s)
    echo "$target"
}

echo "[backup] 服务启动，先立即做一次全量备份"
backup
if [ "$ONCE" = "1" ]; then
    echo "[backup] ONCE=1，单次模式退出"
    exit 0
fi

while true; do
    now=$(date +%s)
    target=$(next_target "$now")
    echo "[backup] 下次备份: $(date -d @"$target" '+%F %T')"
    sleep $((target - now))
    backup
done
