#!/bin/sh
set -e

working_dir=/opt/server
working_dir_bak=/opt/server_backup
marker_file="$working_dir/.initialized"
db_host="${DB_HOST:-beidou-db}"
db_port="${DB_PORT:-3306}"

mkdir -p "$working_dir"

if [ ! -f "$marker_file" ]; then
    echo "First run - initializing volume from image backup (about 650M)..."
    cp -r "$working_dir_bak"/* "$working_dir"/
    touch "$marker_file"
    echo "Initialization complete. Backup kept at $working_dir_bak for recovery."
fi

cd "$working_dir"

# compose 已有 mysql healthcheck 门控，这里对端口可达性再兜一层，
# 超时 120s 后放行交给 java 侧失败 + restart 策略重试
echo "Waiting for database ${db_host}:${db_port} ..."
i=0
while ! nc -z "$db_host" "$db_port" 2>/dev/null; do
    i=$((i + 1))
    if [ "$i" -ge 60 ]; then
        echo "Database not reachable after 120s, starting anyway (restart policy will retry)..."
        break
    fi
    sleep 2
done

exec java ${JAVA_OPTS} -jar ./BeiDou.jar --spring.config.location=./application.yml "$@"
