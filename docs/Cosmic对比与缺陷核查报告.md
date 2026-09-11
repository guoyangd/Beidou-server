# BeiDou-Server 与 Cosmic-Server 对比及缺陷核查报告

> 记录日期：2026-09-05
> BeiDou 基准：master `1321f6120`（发布 1.12 之后的修复雇佣商店改名后结算异常）
> Cosmic 基准：master `fec53bc77`（2026-02-03）
> 方法：Cosmic 侧有一份 `docs/代码审查报告.md`（12 P0 / 24 P1 / 28 P2），本报告逐项打开 BeiDou 对应源码核对逻辑后给出结论；所有结论附 BeiDou `file:line` 证据。
> 用途：**先把程序跑起来，本文件作为后续优化的修复待办清单**（见第三部分路线图）。

---

## 第一部分：项目差异对比

### 1. 定位与血统

| | Cosmic-Server | BeiDou-Server |
|---|---|---|
| 定位 | GMS v83 原味（vanilla）模拟器，"去技术债、拒绝自定义玩法" | 基于 Cosmic 的汉化 + 商业化运营私服（北斗），版本 1.12（`ServerConstants.BEI_DOU_VERSION`） |
| 血统 | OdinMS→HeavenMS 一脉（GitHub P0nk/Cosmic） | 从 Cosmic 较早快照重新初始化的独立仓库（2886 个自有提交，git 历史不连通） |
| 基线时点 | HEAD 含"删除 server_queue 表"（41adae827）、修复任务 21010 奖励（02380aeee） | BeiDou 仍有 `V1.0.40__create_server_queue.sql` 建表迁移——**Cosmic 2026 年初的修复未同步进来** |

### 2. 技术栈对照

| 维度 | Cosmic | BeiDou |
|---|---|---|
| 构建形态 | 单模块 Maven，assembly fat jar（`Cosmic.jar`） | 双模块：`gms-server`（Spring Boot repackage `BeiDou.jar`）+ `gms-ui`（Vue 3 管理后台） |
| 应用框架 | 无任何框架，main 类 `net.server.Server` | Spring Boot 3，主类 `org.gms.ServerApplication`，`ServerManager` 桥接 Netty 游戏服单例 |
| Java / 网络 | Java 21 + Netty 4.2 | Java 21 + Netty（同源） |
| 持久层 | 原生 JDBC（67 文件手写 PreparedStatement）+ HikariCP + JDBI 仅 1 个示范 DAO | MyBatis-Flex（84 entity + 84 mapper）+ Druid + 遗留 JDBC 混用 |
| Schema 迁移 | Liquibase（24 建表 + 13 数据 SQL，changelog-root.xml） | Flyway（V1.0.x ~ V1.10.x，启动自动建库） |
| 配置 | 单个 `config.yaml`（455 行扁平 k-v，yamlbeans 反射绑 public 字段，启动读死无热更） | `application.yml` + `game_config` 表动态配置（`GameConfig` 单例，type→subType→code 树，热重载并即时写回世界倍率） |
| Web/管理 | 完全没有 HTTP/REST/Web 后台，管理靠游戏内 GM 命令 + 33 个离线 mapletools | 完整 REST API（8686 端口，JWT + 限流 + Swagger，15 Controller / 30+ Service）+ Arco Design Vue 后台（构建产物嵌入 jar 同源托管） |
| i18n | 无。封包字符集 4 选 1（US-ASCII 等），文案硬编码英文 | 服务端 `I18nUtil` 三类资源文件（exception/log/message，中英）；双语 wz 覆盖机制（`wz/` 英文基础 + `wz-zh-CN/` 中文覆盖，`LocalizedDataProvider`） |
| 脚本 | GraalVM JS，scripts/ 共 1915 个 js | GraalVM JS（相同）；scripts/ 1975 + scripts-zh-CN/ 2051 个 js（双语覆盖） |
| 部署 | Dockerfile + docker-compose（单容器全服） | launch.bat/sh 捆绑 JRE 21，单 jar；无 Docker 配置 |

### 3. 文件级分叉统计

两库同路径 Java 文件 797 个，内容 **100% 有差异**（包名 `→org.gms.*`、日志 i18n 化、局部重构所致，逐字节 diff 无意义）：

- Cosmic 独有 60 个：36 个 `tools/mapletools`（离线工具，BeiDou 删除）、`config/YamlConfig`、`database/*`（Liquibase/JDBI）、`tools/Pair` 等（BeiDou 移到 `org.gms.util` 并改造）。
- BeiDou 独有 327 个：整个 Spring 胶水层（controller 15 / service 30 / dao 168 / config / aop / manager / exception）、`util` 新增工具（JwtUtils、RateLimitUtil、I18nUtil、LRUCache、ThreadLocalUtil 等）、`net/encryption` 4 个新文件、`server/quest` 4 个、`CharacterListener`、`SystemRescue` 等。

### 4. 架构级差异要点

- **双引擎同进程**：一个 JVM 跑 Spring Boot + Netty 游戏服，`Server`（Odin 式单例）通过 `ServerManager.getApplicationContext().getBean()` 反向取 Spring 依赖；REST 可在线启停游戏服（`/server/v1/startServer` 等）。
- **存档与删除链路重写**：角色保存/删除走 Spring `@Transactional` + Mapper（Cosmic 是手写 JDBC + 无事务删 20+ 表）。
- **BeiDou 自有修复**（Cosmic 没有的）：雇佣商店重复实例与关闭竞态、雇佣商店改名后结算异常、联盟人数超 127 溢出导致聊天失败、好友单向好友系列问题、魔力恢复公式、死锁优化等。

---

## 第二部分：Cosmic 代码审查缺陷在 BeiDou 中的核查结果

### 总览

| 级别 | ✅ 已修复 | ⚠️ 部分修复 | ❌ 仍存在 |
|---|---|---|---|
| P0（12） | **2** | 3 | **7** |
| P1（24） | **4** | 0 | **20** |
| P2（28） | **3** | 2 | **23** |
| 合计（64） | **9（14%）** | **5** | **50（78%）** |

**核心结论**：BeiDou 修的主要是自己重构过的模块（持久化/事务/Netty 生命周期/雇佣商人），而**战斗、经济、登录会话**三块的遗留逻辑几乎原样继承——Cosmic 报告里最致命的 P0 链路（负数伤害秒杀、MTS 溢出刷 NX、Duey 复制）在北斗依然可被利用。

> 以下路径均相对 `gms-server/src/main/java/org/gms/`（行号为核查当日 master 状态）。

### ✅ 已修复（9 项）

| # | 缺陷 | BeiDou 修复方式与证据 |
|---|---|---|
| P0-7 | Fredrick 领取无门禁→雇佣商人营业期间循环复制 | `client/processor/npc/FredrickProcessor.java:285-290` 加 `hasMerchant()` / 世界注册表双重门禁（命中拒绝并提示先关店）；`:304-309` 领取删库后 `merchant.clearItems()`（`server/maps/HiredMerchant.java:560-564`，synchronized）清空货架，切断"同伙买走剩余商品→写回 DB→再领一轮"的循环 |
| P0-10 | 自动存档线程无锁快照背包→物品丢失/回档 | `client/inventory/Inventory.java:105-112` `list()` 已改为持锁 `new ArrayList<>(inventory.values())` 拷贝快照；`client/Character.java:7760-7768` 存档经 `iv.list()` 收集；`:7542-7558` 无参 `saveCharToDB()` 另经 `CharacterSaveService` 按角色串行调度 |
| P1-3 | DISTANCE_HACK 只 alert 永不 addPoint | `net/server/channel/handlers/AbstractDealDamageHandler.java:604-623` 重写为收集最严重越界样本统一 `addPoint`；`client/autoban/AutobanFactory.java:52` 配 10 分/2min 衰减 |
| P1-10 | Aran HIGH_DEFENSE 减伤公式失效（`Math.ceil(0.7)=1.0`） | `net/server/channel/handlers/TakeDamageHandler.java:276-280` 去掉 `Math.ceil`，直接 `damage *= (X / 1000.0)`，恢复 30% 减伤 |
| P1-20 | Netty EventLoopGroup 局部变量无法 shutdown | `net/netty/LoginServer.java:13-14,39-40`（及 ChannelServer 同构）提升为实例字段，`stop()` 中 `shutdownGracefully()` |
| P1-24 | LoginBypassCoordinator 用 `Pair<String,Integer>` remove `Pair<Hwid,Integer>` 键→EnableAuth 永不生效 | `net/server/coordinator/login/LoginBypassCoordinator.java:80-84` 键类型已修正（注释明写"修复 key 类型 bug"）；`:62-78` 注册侧还用 `compute()` 原子化；`Hwid` 为 record 值语义 |
| P2-12 | 商店 BUY 槽位无边界检查（byte 负值/越界 IOOBE） | `server/maps/PlayerShop.java:263` 与 `server/maps/HiredMerchant.java:293` 补齐 `quantity<1 \|\| item<0 \|\| item>=items.size()` 检查 |
| P2-23 | DatabaseConnection 初始化失败日志不带异常/半初始化/无 close API | 架构性重写为 Spring DataSource 薄门面（`util/DatabaseConnection.java:16-18` 仅一行 getBean().getConnection()），生命周期归容器管（Druid，application.yml:16） |
| P2-27 | 删角色 20+ 表无事务 | 重构为 `service/CharacterService.java:237-244` `@Transactional deleteCharFromDB` → `:250-344` 全 Mapper 删除；`client/Character.java:2236-2241` 委托真 Spring 代理。小豁口：`:334-340` `characterexplogs` 删除仍走独立连接且异常被吞（仅影响经验日志）。另：`net/server/Server.java:1244-1262` `deleteCharacterEntry` 的 NPE 风险**本体仍在**，但在线删号前置校验（CharacterService.java:240）+ GM 后台 `safeDeleteCharacterEntry`（:534-540）兜底了两条主路径 |

### ⚠️ 部分修复（5 项）

| # | 缺陷 | 现状 |
|---|---|---|
| P0-6 | Duey 领取/删除不校验收件人→窃取包裹 + 双开竞态复制 | **领取路径已修**：`client/processor/npc/DueyProcessor.java:401,422-427` 改 `static synchronized` + 逐字段校验收件人（不匹配 autoban+断开），注释明写"修复复制外挂多人取同一个快递"。**删除路径未修**：`:139-149` `DELETE FROM dueypackages WHERE PackageId=?` 仍无 `AND ReceiverId=?`，`net/server/channel/handlers/DueyHandler.java:53-56` packageId 完全来自封包，可恶意销毁他人包裹（损毁型，非复制） |
| P0-8 | MTS 上架"按总持有量校验、按单槽位扣除"+ 空槽 NPE | 已补：空槽 null / itemId 一致性校验（`MTSHandler.java:136-139`）、上架数 ≤10、5000 meso 手续费。未补：数量仍按 `getItemQuantity(itemid,false)`（全背包总量）校验而 `removeFromSlot` 只扣单槽，缺 `checkItem.getQuantity() >= quantity`——**4 叠×50、quantity=200 净增 150 的复制手法依然可行**（`client/inventory/Inventory.java:303-309` 超扣静默钳位到 0 放大了问题） |
| P0-12 | ItemFactory 读路径不取条带锁 vs 写路径"删全插" | **写路径已加事务**：`client/inventory/ItemFactory.java:205-215` 检测 autoCommit 自管事务 + rollback（注释明写"避免 DELETE 已提交但 INSERT 失败导致物品丢失"），`:285-296` commit/rollback，merchant 版 `:366-371` 同样处理。**读路径仍无锁**：`:163-166` `loadItemsCommon` 无 lock 调用。事务原子性已基本消除"读到删后插前空窗"的实际后果，仅剩 READ UNCOMMITTED 脏读理论风险 |
| P2-20 | 三处 PreparedStatement 依赖连接归还级联关闭 | `net/server/Server.java:596-604` nxcoupons 已 service 化 ✅；`net/server/world/World.java:1986-2004`（ps.close 不在 finally）与 `net/server/task/RankingLoginTask.java:43-48`（无显式关闭）未改 |
| P2-24 | ThreadManager 拒绝策略裸开非池线程；stop 无 shutdownNow | `server/ThreadManager.java:46` 已改 `newVirtualThreadPerTaskExecutor()`（消除拒绝策略问题）；`:49-56` `stop()` 仍无 `shutdownNow()` 兜底，awaitTermination 返回值被忽略 |

### ❌ 仍存在——P0 级 7 项（可复制/刷钱/秒杀，最优先修）

#### P0-1 负数伤害编码绕过全部上限校验 → 单包秒杀（含 Boss）
- 解码侧 `net/server/channel/handlers/AbstractDealDamageHandler.java:334-338`：对所有负数无差别 `eachd += Integer.MAX_VALUE`。
- 校验侧 `:989-996`：`damage > maxWithCrit*1.5` / `*5` 两道检查只对正数有效；parseDamage 全程无 `damage < -1` 的 autoban/clamp。
- 道场限伤 `:322-331`：`i < dmgLimit ? i : dmgLimit` 对负数原样放行，30% 上限同样被绕过。
- 下游无钳位：`server/maps/MapleMap.java:1346-1358` → `server/life/Monster.java:412-427` 对 `damage > 0` 直接 applyDamage。
- 改包 `damage=-6` → 解码 2,147,483,641 → 任意 Boss 血量归零。近战/远程/魔法三入口全部适用。
- **修复建议**：parseDamage 读入后先规范化负数编码并参与上限校验；`damage < -1` 且反解值超限直接 `DAMAGE_HACK.autoban`；applyAttack 解码后二次 clamp 作纵深防御。

#### P0-2 skill=0（普攻）完全跳过目标数与行数校验
- `AbstractDealDamageHandler.java:139`（`if (attack.skill != 0)` 门）→ `:178-181` mobCount 检查在其内；`:1003-1010` 行数检查在 `if (effect != null)` 内，而 effect 仅 `:761-763` 在 skill!=0 时赋值。
- numAttacked 从半字节读出（`:666`，最大 15）——普攻可声明 15 目标×15 行，与 P0-1 叠加为零校验秒杀。
- **修复建议**：`skill == 0` 时强制 `numAttacked <= 1 && numDamage <= 1`（GM 白名单除外）。

#### P0-3 MTS 购买税额 int 溢出 → 一次操作刷数十亿 NX
- `net/server/channel/handlers/MTSHandler.java:429`（op16）与 `:486`（op17）：`int price = rs.getInt("price") + 100 + (int)(rs.getInt("price") * 0.1);`
- 上架侧 `:105,109` 只检查 `price < 110` 无上限（price 直接 `readInt`）。
- `:461/:515` `gainCash(4, -price)`；`server/CashShop.java:289-295` 纯 `+=` 无下限——总价溢出为负后变成给买家加钱，同时离线卖家 `UPDATE accounts SET nxPrepaid += 原价`，双边刷数十亿 NX，可无限重复。
- **修复建议**：全程 long 计算；上架价设上限；总价超 Integer.MAX_VALUE 拒绝。

#### P0-4 MTS op17（购物车购买）卖家离线时按频道数重复入账
- `MTSHandler.java:488-505`：卖家入账在 `for (Channel cserv : ...)` 循环的 else 分支里——每个未找到卖家的频道都执行一次 `UPDATE accounts SET nxPrepaid = nxPrepaid + ?`（op16 的 alwaysnull 标志 `:431-451` 是对的，op17 没有等价物）。
- **修复建议**：仿 op16 加标志位，或把 DB 入账移出频道循环。

#### P0-5 Duey 快递负数量 → 发件人堆叠凭空膨胀（无限复制）
- `client/processor/npc/DueyProcessor.java:322`：仍只挡 `finalcost < 0 || finalcost > Integer.MAX_VALUE || (amount < 1 && sendMesos == 0)` 组合（amount=-1000、mesos=1000 通过）；`:258` `item.getQuantity() >= amount` 对负数恒真；`:266` 原始 amount 直接传 removeFromSlot。
- `client/inventory/Inventory.java:298-310`：`removeItem` 对 `quantity <= 0` 不提前返回，负数量使堆叠 +1000（可超 slotMax、可 short 溢出）。
- **修复建议**：`amount < 1` 一律拒绝；`Inventory.removeItem` 对 `quantity <= 0` 直接 return。

#### P0-9 商城戒指无余额校验 → 免费购买
- 0x1D Crush Ring（`net/server/channel/handlers/CashOperationHandler.java:347-379`）：无任何 canBuy/余额检查，`toCharge` 是客户端可控 readInt，`gainCash(toCharge, itemRing, ...)`（`server/CashShop.java:297-302`）无下限——toCharge=0 时 switch 无匹配完全免费，=4 时可打成负余额无限购买，且每次给对方免费 gift 同款戒指。
- 0x23 Friendship Ring（`:417-418,436`）：仍 `readByte()+skip(3)` 且 payment 无白名单。注：BeiDou 的 `readInt` 是小端（`ByteBufInPacket.java:35-37`），"恒 0 从不扣款"子症状可能不复现，但改包免费购/负余额缺陷完整保留。
- canBuy 已定义（`:533-540`）但这两处未调用。
- **修复建议**：两处补 canBuy；payment 改 `readInt()` 并白名单 {1,2,4}。

#### P0-11 SessionCoordinator 核心容器非线程安全
- `net/server/coordinator/session/SessionCoordinator.java:69-70`：`onlineClients` 裸 HashMap / `onlineRemoteHwids` 裸 HashSet；`updateOnlineClient`（`:115-121`）仍是先 get 后 put 的非原子写。
- HashMap 结构损坏可破坏"同账号唯一在线"约束——这是防多开/防复制的前置防线。
- **修复建议**：改 `ConcurrentHashMap` / `newKeySet()`；`updateOnlineClient` 用 `compute()` 原子化。

### ❌ 仍存在——P1 级 20 项

**反作弊失效（4 项）**

| # | 问题 | 证据 |
|---|---|---|
| P1-1 | miss godmode 检测死代码（lastmisses 只在 samemisscount>0 时更新，而 samemisscount++ 需要 lastmisses==misses && misses>6，初始 0 永远矛盾→sendPolice 永不触发） | `client/autoban/AutobanManager.java:83-94` 与 Cosmic 逐字相同 |
| P1-2 | HealOvertime HP 快速治疗检测后不 return（检测归检测、回血归回血） | `net/server/channel/handlers/HealOvertimeHandler.java:50-66`（`:63` 照常 addHP；MP 路径 `:72-73` 有 return） |
| P1-4 | MOB_COUNT/FIX_DAMAGE/FAST_ATTACK 瞬封 + 行数检查在循环内重复积分（单包 15 行拉满 15 分误封） | `AbstractDealDamageHandler.java:179`、`:518`、`:98`、`:959`+`:1003-1010`；`AutobanFactory.java:51,182-187`。附注：BeiDou 新增了滑动窗口 ATTACK_INTERVAL 检测（`:1337-1367`），但旧三处瞬封原样保留 |
| （对照 P1-3 已修复） | — | — |

**登录/会话（5 项，玩家可见卡死）**

| # | 问题 | 证据 |
|---|---|---|
| P1-5 | PlayerLoggedinHandler 拿不到 client 锁缺 return→登录照常跑 + finally 对未持有锁 unlock（IllegalMonitorStateException） | `net/server/channel/handlers/PlayerLoggedinHandler.java:117-122`（失败分支只发包）、`:479-481`（finally 无条件 releaseClient）；`client/Client.java:1332-1344` |
| P1-6 | 密码输错一次后同连接重试永远报 17 "Already logged in"（loginok=4 也占用 hwid 直到断 TCP） | `client/Client.java:712-713`（中文注释已意识到问题但代码未改）；`SessionCoordinator.java:203,209-212` |
| P1-7 | BCrypt 迁移分支 loginok=-10 未调 attemptLoginSession（hwid=null）→选角必报 17；hwid=null 的 client 进 onlineClients 无法清理 | `net/server/handlers/login/LoginPasswordHandler.java:85-96`；`Client.java:728-730,572-585,826-830`；`CharSelectedHandler.java:46,71`；`SessionCoordinator.java:164-177,232-235` |
| P1-8 | PIN/PIC 超限后 closeSession(this,false) 不 return 也不断链→每连接猜 5 次重连即重置，可暴力枚举 PIC；pin 列 NULL 时 NPE 死等 | `client/Client.java:603-618,636-651`；`SessionCoordinator.java:320-322`；`Client.java:678`；`net/server/handlers/login/AfterLoginHandler.java:40,47,54` |
| P1-9 | **AUTOMATIC_REGISTER 在北斗默认开启且无速率限制，可脚本无限刷号；注册 INSERT 不写 hwid/macs** | `LoginPasswordHandler.java:64-83`；唯一限制 `Client.java:656-661`（每连接 5 次，重连即重置）；`db/migration/V1.7.0__create_game_config.sql:225` 中 `automatic_register` 默认 `'true'`——**比 Cosmic 更严重，建议立即改 false** |

**数值/战斗（1 项）**

| # | 问题 | 证据 |
|---|---|---|
| P1-11 | Magic Guard 在 `mpattack != 0`（MpBurn/Deadly）时整体跳过→法师魔法盾失效、HP 承受全额 | `net/server/channel/handlers/TakeDamageHandler.java:283`（`&& mpattack == 0` 原样）、`:188-192`、`:304-308` |

**并发（6 项）**

| # | 问题 | 证据 |
|---|---|---|
| P1-12 | summons(LinkedHashMap) 读写只有一半在 chrLock 内 | `client/Character.java:770-772`（addSummon 裸奔）、`:5495-5501`（getSummonsValues/clearSummons 裸奔返回 LIVE 视图）vs `:3478-3505`（锁内）；锁外使用方 `BeholderHandler.java:56`、`SummonDamageHandler.java:78`、`MoveSummonHandler.java:40`、`MapleMap.java:2765` |
| P1-13 | controlMonster/stopControllingMonster tryLock 竞争失败静默丢弃集合更新→离图漏放怪、控制器悬空 | `client/Character.java:1879-1897`（对比 `:1900,1920` 的阻塞 lock，只有这两个 tryLock 漏网） |
| P1-14 | beholder/berserk/dragonBlood 定时任务锁外 check-then-act，取消后可被定时器"复活"→幽灵 buff | `client/Character.java:1846-1855`、`:4116-4127`、`:6905-6908`（锁外注册）vs `:3515-3527`（锁内取消）；旁证 `:4157` recoveryTask 已加锁 |
| P1-15 | CPQ buffMonsters 无锁遍历 mapobjects.values()（全文件唯一漏网）→CME | `server/maps/MapleMap.java:4507-4517`（其余 14 处遍历均持 objectRLock） |
| P1-16 | BuddyList.pendingRequests 完全无锁；getBuddies() 返回 LIVE 视图锁外迭代 | `client/BuddyList.java:52,114-118,154,171-173,199`；锁外迭代例 `:93` |
| P1-17 | MapleMap.getCharacters() 返回活动视图，20+ 调用方锁外迭代可 CME | `server/maps/MapleMap.java:3321-3328`（拿读锁只为包装）vs 写侧 `:2406-2413`；无锁迭代调用方：`MapleMap.java:376`、`UseCashItemHandler.java:402,475`、`AdminCommandHandler.java:114`、`NPCConversationManager.java:515`、OxQuiz/Snowball/Coconut、gm3 命令等；连 `new ArrayList<>(getCharacters())` 拷贝本身也在锁外 |

**生命周期/经济（4 项）**

| # | 问题 | 证据 |
|---|---|---|
| P1-18 | EIM dispose() 只 catch 受检异常，GraalJS RuntimeException 使清理在 disposed=true 前中断→副本泄漏；EventManager.cancel 循环无逐项保护 | `scripting/event/EventInstanceManager.java:651-656,253-258`；`scripting/event/EventManager.java:131-133,145-147` |
| P1-19 | NPC/任务脚本会话旁路残留：removePlayer 前置重逻辑抛异常时 finally 的 clear() 不触碰 cms/scripts→静态 map 强持有 Client→Character | `client/Client.java:930-936,956,1098-1112`；`scripting/npc/NPCScriptManager.java:47-48` |
| P1-21 | 交易 CONFIRM 后仍可 SET_ITEMS（Trade.addItem 不检查 locked；items.clear() 未同步）→经典 dupe 前置 | `server/Trade.java:217-232`（addItem 无 locked 检查，对比 setMeso `:198` 有 throw）、`:124,179`（clear 未同步）；入口 `net/server/channel/handlers/PlayerInteractionHandler.java:506-588`（`:574` 直接 addItem，全文件 grep "locked" 零命中） |
| P1-22 | 现金库存放入（0x0E）与 MTS 上架缺 isUntradeable/isUnmerchable 校验→绑定/任务物品洗白 | `CashOperationHandler.java:328-346`（只加了 invType 越界/宠物/婚戒检查）；`MTSHandler.java:60` 还留着 TODO `// TODO add karma-to-untradeable flag on sold items here`；校验 API 齐备且 PlayerInteractionHandler.java:526/601/605 已用，唯独这两处没接 |
| P1-23 | MTS buy 无 transfer=0 过滤（已售商品可再买）；op8 转移 SELECT→DELETE→发物非事务且不检查 DELETE 行数→竞态双份 | `MTSHandler.java:425,482`（`SELECT * FROM mts_items WHERE id=?` 无 transfer 条件）；`:299-352`（op8：executeUpdate 返回值忽略、无 setAutoCommit(false)） |

### ❌ 仍存在——P2 级 23 项

| # | 问题 | 证据（BeiDou） |
|---|---|---|
| P2-1 | `inRangeInclusive` 逻辑写反（`\|\|` 应为 `&&`），怪物活动值上界校验失效 | `net/server/channel/handlers/MoveLifeHandler.java:181-183`（`return !(pVal < pMin) \|\| (pVal > pMax);`）；`:84-85` |
| P2-2 | WEDDING_TALK 与 WEDDING_TALK_MORE 同码 0x8B，后注册覆盖前者 | `net/opcodes/RecvOpcode.java:145-146`；`net/PacketProcessor.java:309-310,100-101` |
| P2-3 | initChannel 阶段 closeSession(client,true) 时 ioChannel 必为 null→NPE | `net/netty/ChannelServerInitializer.java:37-43`；`SessionCoordinator.java:320-322`；`client/Client.java:291-293` |
| P2-4 | getLoginState 带写副作用：transition 窗口内任何调用清 DB 状态却返回 1 | `client/Client.java:886-894`（UPDATE loggedin=0 后 return state）；`:875` 判空是修另一处 NPE |
| P2-5 | changeChannel 尾部发包失败仅 printStackTrace→半迁移卡死；transitioningChars 无 TTL 泄漏 | `client/Client.java:1563-1568,1541-1543,1115-1118`；`net/server/Server.java:117,1524,1539,1555`（仅按需 remove，无清理任务） |
| P2-6 | closeSession(null,...) → fetchInTransitionSessionClient(null) NPE（潜伏） | `SessionCoordinator.java:297-300`（有 if null 的形但传入仍是 null）、`:277-278,102-106` |
| P2-7 | pickLoginSessionHwid 取出即删不可回滚 | `SessionCoordinator.java:325-329`；`HostHwidCache.java:38-41`；唯一调用点 `PlayerLoggedinHandler.java:144-148`（后续 `:155-158,169-171` 失败即永久丢 HWID）；非破坏接口 `getEntryHwid` 存在但未用 |
| P2-8 | setTimestamp 秒级时间戳：换频道 1 秒 3 次/整理背包 1 秒 4 次即断开（正常操作误伤） | `ChangeChannelHandler.java:40`（阈值 3）、`InventorySortHandler.java:296` / `InventoryMergeHandler.java:43`（阈值 4）；慢时钟 777ms：`net/server/Server.java:187-197,785,789`、`V1.7.0 SQL:33`（update_interval=777）；`AutobanManager.java:127-141` |
| P2-9 | FAST_HP_HEALING expire=-1 永不衰减→挂机误封窗口 | `client/autoban/AutobanFactory.java:58,88-92`（两参构造 expiretime=-1）；新增 DB 覆盖机制 `:149-155` 但 `V1.10.1__create_autoban_config.sql` 无种子数据回落 -1；`AutobanManager.java:52-59`（-1 时永不衰减） |
| P2-10 | gainExp 负增益翻转为 Integer.MAX_VALUE | `client/Character.java:2944-2950`（"integer overflow, heh." 注释原样）；缓解：`:2961-2962` 新增 loseExp 走 `:2980-2981` gainExpInternal（long 正确处理负值），但翻转分支本体未删 |
| P2-11 | Trade.setMeso 全量累加（2M→1M 实付 3M）；completeTrade/cancel 忽略 addFromDrop 返回值→满包退回物品静默消失 | `server/Trade.java:205-208,127-130,162-164`；completeTrade 前有 fits 预检 `:334-370`（Cosmic 同款非新增），cancel 路径无预检 |
| P2-13 | StorageProcessor slot 边界差一 + 溢出钳位分支写反（存满功能失效） | `client/processor/npc/StorageProcessor.java:69`（`slot > storage.getSlots()` 应为 >=，且对照总槽数而非类型过滤表）；`:213-218`（存入分支 `meso < playerMesos` 写反）；`server/Storage.java:221`（越界 IOOBE） |
| P2-14 | CashIdGenerator 发号不落库，重启后重发相同 cashId→现金库存取错物品 | `util/CashIdGenerator.java:36-55,64-76`（仅启动时从 rings/pets 加载，runningCashId 从 0 爬；"no need to do this" 注释原样）；调用点 `net/server/Server.java:716` 仅一次 |
| P2-15 | Guild.gainGP 非原子 `gp +=`（丢更新）；increaseCapacity check-then-act；writeToDB 无锁快照 | `net/server/guild/Guild.java:67,693-695,676-680,159-182` |
| P2-16 | Party.getMemberByPos 无锁遍历；setLeader 写非 volatile 字段 | `net/server/world/Party.java:282-291,56,104-106` |
| P2-17 | World.queuedGuilds/queuedMarriages 零锁 | `net/server/world/World.java:171-172,759-795`（六个读写方法全裸） |
| P2-18 | Character.empty() 中 questExpireTask 锁外置 null，锁内清理成死代码（questExpirations 永不 clear） | `client/Character.java:9557-9560`（锁外）vs `:9583-9594`（锁内恒 false）；唯一清理在 `:8913-8918` forfeit 路径 |
| P2-19 | Channel.shutdown 漏 cancel 婚礼预约任务；setOngoingWedding 覆盖旧任务不 cancel→旧任务错误关闭新婚礼 | `net/server/channel/Channel.java:221-232`（只遍历 dojoTask）、`:918-925` |
| P2-21 | isNpcScriptAvailable 副作用缓存 GraalJS 引擎（点 PlayerNPC 路径残留） | `scripting/npc/NPCScriptManager.java:54-60`；`scripting/AbstractScriptManager.java:90-101`（双参版写 c.setScriptEngine）；`client/Client.java:1215-1217`；调用点 `NPCTalkHandler.java:100` |
| P2-22 | PlayerStorage.disconnectAll 清 storage 不清 nameStorage | `net/server/PlayerStorage.java:115-120`（nameStorage 定义 `:38`） |
| P2-25 | password 列 NULL→login NPE（客户端死等） | `client/Client.java:683`（getString 无判空）、`:695`（`passhash.charAt(0)`）、`:693-694`（仅 debug 双开关分支提前返回）、`:708`（只捕 SQLException） |
| P2-26 | Monster.maxHpPlusHeal 为 0 时除零（Infinity→满额经验） | `server/life/Monster.java:96`（初始 1 但 `:150` 被 hp=0 覆盖）、`:642-644`（`mobExp / totalDamage` 无守卫）、`:594,672` |
| P2-28 | getLoginState 每包处理最多 2 次 DB 往返（登录高并发热点） | `client/Client.java:854-900,826-842`；热路径调用点 `Client.java:690,575`、`PlayerLoggedinHandler.java:198` |

### 继承自 Cosmic 的"已验证无问题"项（无需处理）

以下在 Cosmic 审查中已验证安全，BeiDou 同源继承，复查时可跳过：数据库连接 try-with-resources 全覆盖、Trade 双确认握手无双完成窗口、金币链路 long 钳位、经验分配 min(curHp, delta) 截断、buff 引擎锁序一致、visibleMapObjects 并发 Set、Pair 作 CHM 键安全、Duey 寄费/Fredrick 先删后发顺序、MoveLife WZ 白名单+MP/冷却双重兜底。

### BeiDou 特有缺陷（线上事故排查发现，2026-09-07/08）

以下问题为 BeiDou 自有代码引入（Cosmic 审查报告之外），源自 2026-09-07 大量"无法登录"投诉的排查：

| # | 问题 | 根因与位置 | 状态 |
|---|---|---|---|
| BD-1 | **wan-host 配置 DDNS 域名时，登录/转服依赖现场 DNS 解析，容器内解析间歇性失败（UnknownHostException）→ 该次登录静默中断**；且选角在解析前已置转场态（CharSelectedHandler:101-102），玩家立即重试撞上 getLoginState 转场态 → 错误码 7（P2-4 放大），表现为"重试两三次才能进"；出商城路径（ChangeMapHandler.enterFromCashShop:201-204）同样中招 → 游戏内秒级弹进弹出。共 7 个入口裸调 InetAddress.getByName（CharSelected/WithPic/RegisterPic×2/ViewAllChar×2/出商城）。局域网玩家走 localhost/lan-host 分支不经 DNS，因此管理员自测永远正常 | `Server.getInetSocket` 返回域名串，调用方现场解析；JVM DNS 成功缓存仅 30s，.xyz DDNS 解析链不稳定 | ✅ 已修复（2026-09-09 二次实施，v2）：`HostIpCache` 缓存 + 后台 30s 周期刷新 + last-known-good 回退 + 启动预热 + **强制 IPv4**（客户端封包 IP 字段仅 4 字节）+ 失败降频日志（首败/每 20 次/恢复）；已用真实域名 ms.guos.xyz 容器内实测解析成功。注：2026-09-08 首版曾随"后台 40001"事故整体回滚——该事故实为 jar 未打包 gms-ui（NoResourceFoundException 被 GlobalExceptionHandler 统一映射为误导性 40001），与本修复无关 |
| BD-2 | **登录失败完全静默**：LoginPasswordHandler 所有失败路径只回错误包不打日志，错误码 4/5/7/13/16/17 不可见，线上"无法登录"无法定位 | `LoginPasswordHandler.java:112-121` | ✅ 已修复（2026-09-08）：loginok!=0 与 finishLogin 失败补 WARN 日志（账号+IP+错误码，i18n） |
| BD-3 | 启动时兜底重置账号登录状态 | — | ➖ 确认已存在：`Server.init()` → `accountService.resetAllLoggedIn()`（Server.java:704），无需重复实现 |

附：当日日志另证实 4 次停服均为管理方主动优雅重启（非崩溃），11:35 一次重启失败为 MySQL 未就绪竞态；`automatic_register` 默认 true + 公网暴露（当日多个云厂商 IP 扫描登录口）仍建议尽快关闭。

---

## 第三部分：修复路线图（待办清单）

> 策略：先跑起来再优化。以下按"上线前必须 → 1-2 天内 → 迭代内"分批，勾选即完成。

### 第〇批：跑通前的两个开关级操作

- [ ] **`automatic_register` 默认值改 false**（P1-9，`V1.7.0__create_game_config.sql:225` + 线上库 update），防脚本无限刷号
- [ ] 确认 MTS/商城/Duey 等高危功能是否先在运营层面关闭（若客户端可用，P0-3/4/5/8/9 都在被利用面内）

### 第一批：经济 P0（上线前必须，均为小改动）

- [ ] P0-3 MTS 税额 long 计价 + 上架价上限 + 总价超 INT_MAX 拒绝（`MTSHandler.java:429,486,105-109`）
- [ ] P0-4 MTS op17 入账移出频道循环或加标志位（`:488-505`）
- [ ] P0-8 MTS 上架补 `checkItem.getQuantity() >= quantity`（`:136-139`）
- [ ] P0-5 Duey `amount < 1` 一律拒绝 + `Inventory.removeItem` 对 `quantity <= 0` return（`DueyProcessor.java:322`、`Inventory.java:298-310`）
- [ ] P0-6（残余）Duey 删除 SQL 加 `AND ReceiverId = ?`（`DueyProcessor.java:139-149`）
- [ ] P0-9 商城戒指两处补 canBuy + payment readInt 白名单（`CashOperationHandler.java:347-379,417-436`）

### 第二批：战斗 P0 + 会话 P0（1-2 天内）

- [ ] P0-1 负伤害规范化校验 + autoban + applyAttack 二次 clamp（`AbstractDealDamageHandler.java:334-338,989-996`）
- [ ] P0-2 skill==0 强制 1 目标×1 行（`:139,178-181,1003-1010`）
- [ ] P0-11 SessionCoordinator 改并发容器 + compute()（`SessionCoordinator.java:69-70,115-121`）
- [ ] P1-5 PlayerLoggedinHandler 失败分支补 return（`PlayerLoggedinHandler.java:117-122`）

### 第三批：P1 功能/误封（迭代内）

- [ ] P1-1 miss godmode 检测重写（`AutobanManager.java:83-94`）
- [ ] P1-2 HealOvertime HP 路径补 return（`HealOvertimeHandler.java:50-66`）
- [ ] P1-4 三处瞬封改积分制 + 行数计分移出循环（`AbstractDealDamageHandler.java:98,179,518,1003-1010`）
- [ ] P1-6/7/8 登录三连：hwid 占用条件、迁移分支补 attempt、PIN 超限断链 + null 归一
- [ ] P1-11 Magic Guard 支持 mpattack 路径（`TakeDamageHandler.java:283`）
- [ ] P1-21 Trade.addItem 检查 locked + clear 同步（`Trade.java:217-232`）
- [ ] P1-22 现金库存/MTS 上架接 isUntradeable/isUnmerchable（API 已有，两处接入）
- [ ] P1-23 MTS buy 加 transfer=0 + op8 事务化
- [ ] P1-12~17 并发补锁（summons/controlMonster/幽灵 buff/buffMonsters/BuddyList/getCharacters 快照化）
- [ ] P1-18/19 EIM dispose 容错 + 脚本会话清理

### 第四批：P2 清单（择机）

- [ ] P2-1 inRangeInclusive 改 `&&`
- [ ] P2-2 婚礼 opcode 撞码 0x8B 分配新码
- [ ] P2-8/P2-9 spam 检测时间粒度 + autoban expire 种子数据（`V1.10.1` 补 FAST_HP_HEALING 配置）
- [ ] P2-10 删除 gainExp 负数翻转分支
- [ ] P2-11 Trade.setMeso 覆盖式 + 退回物品失败补偿
- [ ] P2-13 仓库边界 `>=` + 钳位分支条件修正
- [ ] P2-14 CashIdGenerator 发号落库
- [ ] P2-15/16/17 Guild/Party/World 并发原子化
- [ ] P2-18 questExpireTask 清理顺序修正
- [ ] P2-19 婚礼任务 cancel 补齐
- [ ] P2-20 残余两处 PreparedStatement try-with-resources
- [ ] P2-21 isNpcScriptAvailable 改无副作用探测
- [ ] P2-22 disconnectAll 补 nameStorage.clear()
- [ ] P2-24 ThreadManager.stop 补 shutdownNow
- [ ] P2-25 password null 防护
- [ ] P2-26 maxHpPlusHeal 除零守卫
- [ ] P2-3/4/5/6/7/28 会话健壮性批量小修
- [ ] P2-27 残余：characterexplogs 删除并入事务；Server.deleteCharacterEntry 补空保护

---

*核查方法备注：静态人工级源码逐行核对（代理并行分模块审查），未运行动态验证；P0 结论均附可复核的 file:line 证据。*
