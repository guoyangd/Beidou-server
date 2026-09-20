#!/usr/bin/env python3
# 输出全服在线玩家（含机器人）按地图的分布表：区域汇总 + 地图明细
import json, sys, collections, urllib.request

argv = sys.argv[1:]
base = argv[0] if len(argv) > 0 else "http://beidou-server:8686"
user = argv[1] if len(argv) > 1 else "admin"
pwd = argv[2] if len(argv) > 2 else "admin"

def post(path, payload, token=None):
    req = urllib.request.Request(base + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + token} if token else {})})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())

tok = post("/auth/v1/login", {"requestId": "r", "data": {"username": user, "password": pwd}})["data"]["token"]
rows, page = [], 1
while True:
    d = post("/character/v1/online/list", {"requestId": "r", "data": {"pageNo": page, "pageSize": 100}}, tok)["data"]
    r = d["records"] if isinstance(d, dict) else d
    if not r:
        break
    rows += r
    page += 1

def region(m):
    s = str(m)
    fm_town = lambda: s.startswith("910000")
    if s.startswith("910000"): return "自由市场"
    if s in ("100000000","100000100","100000101","100000102","100000103") or 100000200 <= m <= 100000210 or s.startswith("1000000"): return "射手村城区"
    if s.startswith(("101","102","103","104","10002","10003")): return "维多利亚野外"
    if s.startswith("105"): return "睡眠森林/蚂蚁洞"
    if s.startswith(("200","208")): return "天空之城"
    if s.startswith("211"): return "冰峰雪域"
    if s.startswith(("220","221")): return "玩具城大陆"
    return "其他"

cnt = collections.Counter(x["map"] for x in rows)
reg = collections.Counter()
for m, n in cnt.items():
    reg[region(m)] += n

print(f"在线总数: {len(rows)}   覆盖地图数: {len(cnt)}")
print("\n=== 按区域汇总 ===")
for k, v in reg.most_common():
    print(f"{k:14s} {v:5d}")
print("\n=== 地图明细（前 50，格式 地图ID: 人数）===")
for m, n in cnt.most_common(50):
    print(f"{m:>10}: {n}")
