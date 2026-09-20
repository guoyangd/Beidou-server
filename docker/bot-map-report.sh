#!/bin/sh
# 输出全服在线玩家（含机器人）按地图的分布表
# 用法（在 beidou 的 compose 所在目录执行）：
#   docker run --rm --network beidou_beidou-network -e DBU=admin -e DBP=admin \
#     -v "$(pwd)/bot-map-report.py:/r.py:ro" mysql:8.4.0 python3 /r.py http://beidou-server:8686
# （DBU/DBP 是后台管理员账号密码，默认 admin/admin）
python3 "$0.py" "${1:-http://beidou-server:8686}" "${DBU:-admin}" "${DBP:-admin}"
