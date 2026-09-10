# 14-server-maps 详细设计

- 模块路径：`gms-server/src/main/java/org/gms/server/maps/`
- 类数量：45 个顶层类型（37 个类 + 3 个接口 `MapObject`/`AnimatedMapObject`/`Portal` + 5 个枚举 `MapObjectType`/`FieldLimit`/`SavedLocationType`/`SummonMovementType`/`MiniDungeonInfo`），另有若干内部类（`MapleMap.MobLootEntry`、`MapleMap.ActivateItemReactor`、`ReactorStats.StateData`、`PlayerShop.SoldItem`、`HiredMerchant.Visitor`/`PastVisitor`/`SoldItem`、`MiniGame.MiniGameType`/`MiniGameResult` 等）。注：任务口径中的 `MapleMapFactory`/`MapleMapLayout`/`MapleFoothold`/`MapleReactor`/`JobReturnType` 在本仓库实际命名为 `MapFactory`/`Foothold`/`Reactor` 或不存在，以实际 `ls` 清单为准；刷怪点 `SpawnPoint` 位于 `org.gms.server.life` 包，不在本模块内，仅被本模块引用。
- 依赖模块：
  - `org.gms.client.*`：`Character`、`Client`、`SkillFactory`、`BuffStat`、`StatEffect`、`autoban.AutobanFactory`、`inventory.*`（Item/Equip/Inventory/InventoryType/Pet/ItemFactory/manipulator）、`status.MonsterStatus(Monitor)Effect`
  - `org.gms.net.server.*`：`Server`、`channel.Channel`、`world.Party`/`World`、`coordinator.world.MonsterAggroCoordinator`、`services.task.channel.OverallService`/`MobMistService`、`services.type.ChannelServices`
  - `org.gms.provider.*`：`Data`/`DataTool`/`DataProvider`/`DataProviderFactory`/`provider.wz.WZFiles`（WZ 数据读取）
  - `org.gms.scripting.*`：`event.EventInstanceManager`、`map.MapScriptManager`、`portal.PortalScriptManager`、`reactor.ReactorScriptManager`
  - `org.gms.server.life.*`：`Monster`、`NPC`、`PlayerNPC`、`LifeFactory`、`SpawnPoint`、`MonsterDropEntry`、`MonsterGlobalDropEntry`、`MonsterInformationProvider`、`MonsterListener`
  - `org.gms.server.partyquest.*`：`CarnivalFactory.MCSkill`、`GuardianSpawnPoint`
  - `org.gms.server.events.gm.*`：`OxQuiz`、`Fitness`、`Ola`、`Snowball`、`Coconut`
  - 其他：`org.gms.server.TimerManager`、`Trade`、`ItemInformationProvider`；`org.gms.config.GameConfig`（热更配置）；`org.gms.constants.*`（`game.GameConstants`、`id.MapId`、`id.MobId`、`inventory.ItemConstants`、`skills.*`）；`org.gms.util.*`（`PacketCreator`、`Pair`、`Randomizer`、`DatabaseConnection`、`NumberTool`、`StringUtil`、`I18nUtil`）
  - JDK：`java.awt.Point/Rectangle`、`java.util.concurrent`（`ReentrantLock`/`ReentrantReadWriteLock`/`ScheduledFuture`/`AtomicInteger`/`WeakReference`）

---

## 1. 地图对象模型基础（MapObject 体系）

### 1.1 MapObject（接口）

地图上一切实体的根接口：NPC、怪物、掉落物、玩家、门、召唤兽、商店、迷你游戏、毒雾、反应堆、雇佣商店、玩家 NPC、龙、风筝均实现本接口（源码路径：`server/maps/MapObject.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `int getObjectId()` | 取对象唯一 OID | OID 由 `MapleMap.getUsableOID()` 从 1000000001 起递增分配，到 2147000000（避免与 PlayerNPC 冲突）后回绕 |
| `void setObjectId(int id)` | 设置 OID | 由地图注册时调用 |
| `MapObjectType getType()` | 取实体类别 | 供地图按类型过滤查询（`getMapObjectsInRange` 等） |
| `Point getPosition()` | 取坐标 | 部分实现返回防御性拷贝（`AbstractMapObject`） |
| `void setPosition(Point position)` | 设置坐标 | 掉落物/毒雾等不可移动实体抛 `UnsupportedOperationException` |
| `void sendSpawnData(Client client)` | 向指定客户端发送「生成该对象」的包 | 玩家进图视野同步、对象进入视野范围时调用 |
| `void sendDestroyData(Client client)` | 向指定客户端发送「销毁该对象」的包 | 对象离开视野/被移除时调用 |
| `void nullifyPosition()` | 将内部坐标置 null | 释放已销毁对象的坐标引用 |

### 1.2 AnimatedMapObject（接口）

带朝向/姿态（stance）的地图实体接口，继承 `MapObject`；玩家、怪物、龙、召唤兽实现（源码路径：`server/maps/AnimatedMapObject.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `int getStance()` | 取姿态值 | 姿态编码同时含动作与朝向 |
| `void setStance(int stance)` | 设置姿态值 | — |
| `boolean isFacingLeft()` | 是否朝左 | 实现类约定 `Math.abs(stance) % 2 == 1` |

### 1.3 AbstractMapObject

`MapObject` 的抽象基类，统一持有坐标与 OID（源码路径：`server/maps/AbstractMapObject.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `position` | `Point` | 对象坐标（初始 `new Point()` 即 (0,0)） |
| `objectId` | `int` | 地图分配的 OID，默认 0 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public abstract MapObjectType getType()` | 抽象：子类声明类型 | — |
| `public Point getPosition()` | 取坐标 | 返回 `new Point(position)` 拷贝，防外部改写 |
| `public void setPosition(Point position)` | 设置坐标 | `this.position.move(x, y)` 原地移动而非替换引用 |
| `public int getObjectId()` / `public void setObjectId(int id)` | OID 存取 | — |
| `public void nullifyPosition()` | 坐标置 null | — |

### 1.4 AbstractAnimatedMapObject

带姿态与「空闲移动帧」能力的抽象地图实体，继承 `AbstractMapObject` 实现 `AnimatedMapObject`；`Dragon`、`Summon`（及客户端侧玩家/怪物）基于它（源码路径：`server/maps/AbstractAnimatedMapObject.java`）。

| 字段/常量 | 类型 | 说明 |
| --- | --- | --- |
| `IDLE_MOVEMENT_PACKET_LENGTH` | `static final int`（15） | 预生成空闲移动包字节数 |
| `IDLE_MOVEMENT_PACKET` | `static final Packet` | 类加载时预构的一条「原地移动」包模板 |
| `stance` | `int` | 姿态 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int getStance()` / `public void setStance(int stance)` | 姿态存取 | — |
| `public boolean isFacingLeft()` | 是否朝左 | `Math.abs(stance) % 2 == 1` |
| `public InPacket getIdleMovement()` | 生成一条以当前位置/姿态填充的「空闲移动」入包 | 复制模板字节后**直接按下标覆写** x（[2..3]）、y（[4..5]）、stance（[12]）四个字段，避免每次新建 PacketWriter；供怪物控制器同步等场景复用 |
| `private static Packet createIdleMovementPacket()` | 构造模板包 | 写 1 条移动命令：x=-1、y=-1、wobble=0、fh=0、stance=-1、duration=0（占位值，等待运行时覆写） |

### 1.5 MapObjectType（枚举）

地图实体类别枚举，供 `MapObject.getType()` 与地图的类型过滤查询使用（源码路径：`server/maps/MapObjectType.java`）。

| 枚举值 | 对应实体 |
| --- | --- |
| `NPC` / `PLAYER` / `PLAYER_NPC` | NPC / 玩家 / 玩家 NPC |
| `MONSTER` / `ITEM` | 怪物 / 掉落物 |
| `DOOR` / `SUMMON` / `DRAGON` | 传送门（门的图示）/ 召唤兽 / 龙坐骑 |
| `SHOP` / `HIRED_MERCHANT` / `MINI_GAME` | 玩家商店 / 雇佣商店 / 五子棋·记忆配对 |
| `MIST` / `REACTOR` / `KITE` | 毒雾等区域雾 / 反应堆 / 风筝（留言） |

---

## 2. MapleMap（核心大类）

一张运行中的地图实例：聚合地图上全部实体（`mapobjects`）、在线玩家（`characters`）、刷怪点（`monsterSpawn`）、掉落物生命周期（`droppedItems`/`registeredDrops`）、传送门（`portals`）、地形（`footholds`）与各类定时监控任务；每个「世界-频道（-活动实例）」各持一份（源码路径：`server/maps/MapleMap.java`，4671 行）。

### 2.1 关键字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `mapobjects` | `LinkedHashMap<Integer, MapObject>` | 全部地图实体（oid → 对象），读写锁保护 |
| `characters` | `LinkedHashSet<Character>` | 在图玩家，读写锁保护 |
| `mapParty` | `Map<Integer, Set<Integer>>` | 在图各队伍 → 成员 cid 集合 |
| `portals` | `Map<Integer, Portal>` | 传送门（id → 门） |
| `monsterSpawn` / `allMonsterSpawn` | `Collection<SpawnPoint>`（同步 List） | 常规刷怪点 / 全量刷怪点（含事件重置用副本，来自 `server.life.SpawnPoint`） |
| `spawnedMonstersOnMap` | `AtomicInteger` | 当前存活怪物计数 |
| `droppedItemCount` | `AtomicInteger` | 当前掉落物计数（配合 `item_limit_on_map` 上限） |
| `droppedItems` / `registeredDrops` | `Map<MapItem, Long>` / `LinkedList<WeakReference<MapObject>>` | 掉落物 → 过期时间戳；弱引用队列（超上限时按序清除最旧掉落） |
| `mobLootEntries` | `Map<MobLootEntry, Long>` | 「死亡动画结束后再掉落」延迟队列（`use_spawn_loot_on_animation` 开启时） |
| `statUpdateRunnables` | `List<Runnable>` | 角色属性更新任务缓冲，由 200ms 定时任务批量执行 |
| `footholds` / `mapArea` / `xLimits` | `FootholdTree` / `Rectangle` / `Pair<Integer,Integer>` | 地面碰撞树 / 地图矩形边界 / 可掉落 x 区间缓存 |
| `areas` | `List<Rectangle>` | WZ `area` 节点定义的矩形区域（事件判定用） |
| `environment` / `backgroundTypes` | `Map<String,Integer>` / `Map<Integer,Integer>` | 环境物件状态 / 背景层类型（夜间效果） |
| `mapid`/`world`/`channel`/`returnMapId`/`monsterRate` | `int`/`byte` | 地图 ID、世界、频道、返回城镇、怪物倍率 |
| `runningOid` | `AtomicInteger`（初值 1000000001） | OID 发号器 |
| `event` | `EventInstanceManager` | 所属活动实例（null 为普通地图） |
| `clock`/`boat`/`docked`/`town`/`everlast`/`dropsOn`/`isMuted` | 布尔 | 时钟/船/停靠/城镇/不掉落过期（everlast 地图物品不过期）/掉落开关/禁言 |
| `forcedReturnMap`/`timeLimit`/`mapTimer` | `int`/`long` | 强制返回图 / 时间限制（秒）/ 限时到期时间戳 |
| `decHP`/`protectItem`/`recovery`/`seats` | `int`/`float` | 每秒掉 HP（毒图）、保护物品 ID、恢复率、座位数 |
| `onFirstUserEnter`/`onUserEnter` | `String` | 首人进图/每人进图触发的地图脚本名 |
| `fieldType`/`fieldLimit`/`mobCapacity`/`mobInterval` | `int`/`short` | 地图类型（81/82 强制装备等）/行为限制位/怪物容量上限/刷怪间隔 |
| `aggroMonitor` | `MonsterAggroCoordinator` | 怪物仇恨/控制器协调器（与 itemMonitor 同生共死） |
| `itemMonitor`/`expireItemsTask`/`mobSpawnLootTask`/`characterStatUpdateTask` | `ScheduledFuture<?>` | 空图检测 / 掉落过期 / 延迟掉落 / 属性批量更新四个定时任务 |
| `itemMonitorTimeout` | `short` | 空图宽限计数（连续两个周期无人才停监控） |
| `mapOwner`/`mapOwnerLastActivityTime` | `Character`/`long` | 地图所有权占用者与最后活跃时间（60s 不活跃自动释放） |
| `snowball0`/`snowball1`/`coconut`/`ox`/`isOxQuiz`/`eventstarted` | — | 内嵌 GM 小活动状态（雪球/椰子/OX 答题/活动开始标记） |
| `maxMobs`/`maxReactors`/`deathCP`/`timeDefault`/`timeExpand`/`takenSpawns`/`guardianSpawns`/`blueTeamBuffs`/`redTeamBuffs`/`skillIds`/`mobsToSpawn` | — | CPQ（怪物嘉年华）专属数据 |
| `chrRLock`/`chrWLock`/`objectRLock`/`objectWLock` | `Lock` | 玩家表与实体表的两对公平读写锁 |
| `lootLock` | `ReentrantLock(true)` | 保护 `mobLootEntries` 延迟掉落队列 |
| `bndLock` | `static ReentrantLock(true)` | 保护静态 `dropBoundsCache`（各图掉落 x 边界缓存） |
| `dropBoundsCache` | `static Map<Integer, Pair<Integer,Integer>>` | 进程级「地图 → 可掉落 x 区间」缓存 |

### 2.2 构造与基础属性

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapleMap(int mapid, int world, int channel, int returnMapId, float monsterRate)` | 构造地图 | ① `monsterRate = (byte)Math.ceil(...)`，为 0 时置 1；② 初始化玩家/实体两把公平读写锁；③ 创建 `MonsterAggroCoordinator` |
| `public int getId()` | 地图 ID | — |
| `public int getWorld()` / `public Channel getChannelServer()` / `public World getWorldServer()` | 世界号 / 所属频道服 / 世界服 | 经 `Server.getInstance()` 单例路由 |
| `public EventInstanceManager getEventInstance()` / `public void setEventInstance(EventInstanceManager eim)` | 活动实例存取 | 由 `MapFactory.loadMapFromWz` 装配 |
| `public Rectangle getMapArea()` | 地图矩形边界 | 由 `setMapPointBoundings`/`setMapLineBoundings` 写入 |
| `public void setMapPointBoundings(int px, int py, int h, int w)` | 以「左上角+宽高」设边界 | 用于旧式（无 VR 节点、按 miniMap 推算）地图 |
| `public void setMapLineBoundings(int vrTop, int vrBottom, int vrLeft, int vrRight)` | 以 VR 四边线设边界 | 标准 VR 节点地图 |
| `public MapleMap getReturnMap()` / `public int getReturnMapId()` | 返回城镇地图/其 ID | `returnMapId == MapId.NONE` 时返回自身 |
| `public MapleMap getForcedReturnMap()` / `public int getForcedReturnId()` / `public void setForcedReturnMap(int map)` | 强制返回图（死亡/离开时） | — |
| `public int getTimeLimit()` / `public void setTimeLimit(int)` / `public int getTimeLeft()` | 地图时限（秒）与剩余秒 | `getTimeLeft = (mapTimer - now)/1000` |
| `public String getMapName()`/`setMapName(String)`、`public String getStreetName()`/`setStreetName(String)` | 地名/街道名 | 由 `MapFactory` 从 String.wz 装配 |
| `public boolean isTown()`/`setTown(boolean)` | 是否城镇 | — |
| `public boolean getEverlast()`/`setEverlast(boolean)` | everlast（物品不过期、离开清图）标志 | `registerItemDrop` 中 everlast 图掉落过期时间为 `Long.MAX_VALUE` |
| `public int getHPDec()`/`setHPDec(int)`、`public int getHPDecProtect()`/`setHPDecProtect(int)` | 毒图每秒扣 HP / 保护物品 | `addPlayer` 中 `getHPDec()>0` 时把玩家登记进世界 HP 递减列表 |
| `public float getRecovery()`/`setRecovery(float)` | 恢复率 | — |
| `public int getSeats()`/`setSeats(int)` | 座位数（椅子） | — |
| `public void setMobInterval(short)` / `public short getMobInterval()` | 刷怪间隔（ms） | WZ `createMobInterval`，默认 5000，供 `SpawnPoint` 用 |
| `public void setMobCapacity(int)` / 相关 `mobCapacity` | 怪物容量上限（PyPQ 用） | `spawnMonster` 达到上限即拒绝生成 |
| `public int getSpawnedMonstersOnMap()` | 存活怪物数 | 原子计数 |
| `public void setFieldType(int)` / `public int getFieldLimit()` / `public void setFieldLimit(int)` | 地图类型 / 行为限制位 | `fieldLimit` 供 `FieldLimit.check` 判定 |
| `public OxQuiz getOx()`/`setOx(OxQuiz)`、`public void setOxQuiz(boolean)`/`public boolean isOxQuiz()` | OX 答题活动状态 | — |
| `public void setOnUserEnter(String)`/`getOnUserEnter()`、`setOnFirstUserEnter(String)`/`getOnFirstUserEnter()` | 进图脚本名 | — |
| `public void setMuted(boolean)`/`public boolean isMuted()` | 地图禁言 | — |

### 2.3 玩家进出与查询

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addPlayer(Character chr)` | 玩家进图（约 240 行） | ① 先 `cleanupGhostPlayers()` 清理幽灵玩家；② 持 `chrWLock` 加入 `characters`、登记 `mapParty`、`itemMonitorTimeout=1`；③ `setMapId`、`updateActiveEffects`，毒图登记 HP 递减；④ 若是首人进图：启动 itemMonitor+aggroMonitor，跑 `onFirstUserEnter` 脚本；⑤ 跑 `onUserEnter` 脚本（cygnusTest 存 `INTRO` 位置）；⑥ 禁坐骑图取消骑宠 buff；⑦ 渡轮/天空渡船图（里恩航线、Ereve 航线等 6 图）按交通时长发时钟并在到点 `changeMap`；⑧ 迷你地下城图注册进 `MiniDungeon`；Ariant 竞技场发 10 分钟时钟；⑨ 宠物落地（`getGroundBelow`）并 `showPet`、`commitExcludedItems`；⑩ CPQ 图发开战包；⑪ 移除沙盒道具；黑板处理（自由市场进图自动清除）；⑫ 隐身时仅 GM 可见地广播 spawn（含 DARKSIGHT 外观 buff），否则普通广播；⑬ `sendObjectPlacement` 全量下发视野内对象；⑭ 起始活动图关 `join00` 门；强制装备图发 `showForcedEquip`；椰子图发比分；⑮ 把玩家自身放入 `mapobjects`；⑯ 重建玩家商店/龙/召唤兽；⑰ 补发地图效果、resetForcedStats、Aran 神属性图、活动时钟、雪球图、地图时钟、船状态；⑱ `receivePartyMemberHP`、登记异常状态广播 |
| `public void removePlayer(Character chr)` | 玩家离图 | ① **先** `releaseControlledMonsters()` 重分配受控怪物（防止后续异常导致 controller 卡死在离线玩家——幽灵怪根因）；② 注销椅子 buff；③ 持写锁从 `characters`/`mapParty` 移除；④ 迷你地下城注销，为空则频道移除该 MD 实例；⑤ `removeMapObject`；隐身走 GM 广播否则广播 `removePlayerFromMap`；⑥ `chr.leaveMap()`；⑦ 定驻召唤兽（傀儡）取消 PUPPET buff，其余移除；⑧ 龙移除并广播 |
| `private void cleanupGhostPlayers()` | 清理「已断线未正常移除」的幽灵玩家 | `addPlayer` 被动触发：扫 `characters` 中 `isAwayFromWorld()`（掉线/商城/MTS）者，逐个 `removePlayer`（单个失败不影响其余，也不阻断进图），并打 warn/error 日志 |
| `public Collection<Character> getCharacters()` | 在图玩家只读视图 | 读锁内包 `unmodifiableCollection` |
| `public List<Character> getAllPlayers()` / `public Map<Integer, Character> getMapAllPlayers()` / `public Map<Integer, Character> getMapPlayers()` | 玩家列表/ID 索引 | 均持读锁复制快照 |
| `public int countPlayers()` / `public List<MapObject> getPlayers()` / `public List<MapObject> getAllPlayer()` | 玩家数量与玩家实体 | 经 `getMapObjectsInRange(∞, PLAYER)` |
| `public int countAlivePlayers()` | 存活玩家数 | — |
| `public Character getCharacterById(int id)` / `public Character getCharacterByName(String name)` | 按 ID/名字找人 | 名字匹配忽略大小写 |
| `public List<Character> getPlayersInRange(Rectangle box)` | 矩形内玩家 | — |
| `public void warpEveryone(int to)` / `public void warpEveryone(int to, int pto)` | 全图传送 | 复制玩家快照后逐个 `changeMap`（目标图/目标门） |
| `public void warpOutByTeam(int team, int mapid)` | 按队伍传送 | CPQ 等按 team 分流 |
| `private static void announcePlayerDiseases(Client c)` | 登记异常状态广播 | 委托 `Server.registerAnnouncePlayerDiseases` |

### 2.4 队伍管理

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addPartyMember(Character chr, int partyid)` / `public void removePartyMember(Character chr, int partyid)` | 队员进/出图登记 | 写锁包裹内部方法 |
| `private void addPartyMemberInternal(...)` | 登记实现 | `mapParty` 中该队伍 Set 不存在则新建；`partyid == -1` 直接返回 |
| `private void removePartyMemberInternal(...)` | 移除实现 | Set 仅剩 1 人时整队条目移除 |
| `public void removeParty(int partyid)` | 整队移除 | — |
| `public int getCurrentPartyId()` | 图内任一队伍 ID | 无队伍返回 -1 |
| `public Character getAnyCharacterFromParty(int partyid)` | 取图内该队任一成员 | — |

### 2.5 广播（消息下发）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void broadcastMessage(Packet packet)` | 全图广播 | 委托核心私有方法（范围 ∞） |
| `public void broadcastMessage(Character source, Packet packet, boolean repeatToSource)` | 排除/包含源的全图广播 | `repeatToSource` 为 true 时 source 传 null |
| `public void broadcastMessage(Character source, Packet packet, boolean repeatToSource, boolean ranged)` | 可限距广播 | ranged 时按 `getRangedDistance()`（配置 `use_max_range` 则 ∞，否则 722500=850px²）限距 |
| `public void broadcastMessage(Packet packet, Point rangedFrom)` / `public void broadcastMessage(Character source, Packet packet, Point rangedFrom)` | 以某点为圆心的限距广播 | — |
| `private void broadcastMessage(Character source, Packet packet, double rangeSq, Point rangedFrom)` | 核心广播 | 读锁内迭代：`chrDisconnected` 踢掉 null/client 为 null 者；`chr != source` 且距离平方 ≤ rangeSq 才发送 |
| `public void broadcastGMMessage(Packet packet)` / `public void broadcastGMMessage(Character source, Packet packet, boolean repeatToSource)` / `private void broadcastGMMessage(Character, Packet, double, Point)` | GM 广播 | 仅 `chr.isGM()` 接收 |
| `public void broadcastPacket(Character source, Packet packet)` | 排除源的广播 | 经 `Predicate` 版本，条件含 `getClient() != null` |
| `public void broadcastGMPacket(Character source, Packet packet)` | GM 级别广播 | 条件：`chr.gmLevel() >= source.gmLevel()` |
| `public void broadcastNONGMMessage(Character source, Packet packet, boolean repeatToSource)` | 非玩家广播 | 仅非 GM 接收（GM 隐身观战用） |
| `private boolean chrDisconnected(Iterator<Character>, Character chr)` | 广播时清死链 | `chr == null \|\| chr.getClient() == null` 时 `iterator.remove()` 并返回 true |
| `public void broadcastBossHpMessage(Monster mm, int bossHash, Packet packet)` / `(…, Point rangedFrom)` / `private (…, Character source, Packet, double rangeSq, Point rangedFrom)` | Boss 血条广播 | 经 `Client.announceBossHpBar` 去重（同 hash 血条只刷一次） |
| `private void broadcastItemDropMessage(MapItem, Point dropperPos, Point dropPos, byte mod[, double rangeSq, Point rangedFrom])` | 掉落物出现广播（3 个重载） | 按玩家逐个构造 `dropItemFromMapObject`（mod=0 出现、1 拾取、2 进入视野、3 即现即灭） |
| `public void broadcastSpawnPlayerMapObjectMessage(Character source, Character player, boolean enteringField)` | 广播玩家进图外观 | — |
| `public void broadcastGMSpawnPlayerMapObjectMessage(Character source, Character player, boolean enteringField)` | 隐身玩家进图（仅 GM 可见） | gmBroadcast=true 分支 |
| `private void broadcastSpawnPlayerMapObjectMessage(…, boolean gmBroadcast)` | 上述两者实现 | 读锁内迭代 + 断线剔除 |
| `public void broadcastUpdateCharLookMessage(Character source, Character player)` | 广播外观变更 | — |
| `public void dropMessage(int type, String message)` / `public void broadcastStringMessage(int, String)` | 全图系统消息 | `serverNotice` 广播 |
| `public void broadcastBalrogVictory(String leaderName)` / `broadcastHorntailVictory()` / `broadcastZakumVictory()` / `broadcastPinkBeanVictory(int channel)` | Boss 讨伐胜利全服广播 | 世界频道 `dropMessage(6, 中文捷报文案)`；巴洛古带幸存人数、品克缤带频道号 |

### 2.6 地图实体管理（增删查）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addMapObject(MapObject mapobject)` | 注册实体并分配 OID | `getUsableOID()` 后写锁放入 `mapobjects` |
| `public void addPlayerNPCMapObject(PlayerNPC pnpcobject)` | 注册玩家 NPC（不自增 OID） | 直接以其既有 oid 放入 |
| `public void removeMapObject(int num)` / `public void removeMapObject(MapObject obj)` | 按 OID/对象移除 | — |
| `public MapObject getMapObject(int oid)` | 按 OID 查实体 | — |
| `public List<MapObject> getMapObjects()` | 全部实体快照 | 读锁内 `new LinkedList(mapobjects.values())` |
| `private Map<Integer, MapObject> getCopyMapObjects()` | 实体表拷贝 | `movePlayer` 可见性比对用 |
| `public List<MapObject> getMapObjectsInRange(Point from, double rangeSq, List<MapObjectType> types)` | 距离内按类型查 | `from.distanceSq(pos) <= rangeSq` |
| `public List<MapObject> getMapObjectsInBox(Rectangle box, List<MapObjectType> types)` | 矩形内按类型查 | `box.contains(pos)` |
| `public List<MapObject> getMapObjectsInRect(Rectangle box, List<MapObjectType> types)` | 同上（别名） | — |
| `private int getUsableOID()` | 取可用 OID | 写锁外自增；`>= 2147000000`（避开 PlayerNPC 段）回绕到 1000000001；`mapobjects` 已占用则重试 |
| `private void spawnAndAddRangedMapObject(MapObject, DelayedPacketCreation[, SpawnCondition])` | 注册实体并即时对视野内玩家发包 | 同时持 `chrRLock`+`objectWLock`：分配 OID、放入表、对满足 condition 且距离 ≤ 视野的玩家 `addVisibleMapObject`；**锁外**再逐个 `packetbakery.sendPackets` |
| `private void spawnRangedMapObject(MapObject, DelayedPacketCreation, SpawnCondition)` | 只对视野内玩家发包（不注册） | — |
| `private static void updateMapObjectVisibility(Character chr, MapObject mo)` | 单玩家-单实体视野同步 | 不可见且（召唤兽或进入视野）→ spawn；可见且（非召唤兽且超出视野）→ destroy |
| `public void movePlayer(Character player, Point newPosition)` | 玩家移动 | 更新坐标后：遍历其可见对象做可见性刷新（对象已不在图中则直接移除引用）；再对当前范围内新出现的实体补发 spawn |
| `public void moveMonster(Monster monster, Point reportedPos)` | 怪物移动 | 更新坐标并对全图玩家刷新该怪可见性 |
| `private void sendObjectPlacement(Client c)` | 进图全量下发 | ① 非限距类型（NPC/PLAYER/HIRED_MERCHANT/PLAYER_NPC/DRAGON/MIST/KITE）直接 `sendSpawnData`；② 残留的孤儿召唤兽（owner 是自己但角色召唤列表没有）顺手从图中清除；③ 限距类型按视野下发并 `addVisibleMapObject`，怪物顺带 `aggroUpdateController` |
| `private static boolean isNonRangedType(MapObjectType type)` | 是否「全图可见」类型 | NPC、玩家、雇佣商店、玩家 NPC、龙、雾、风筝 |

### 2.7 怪物生成 / 击杀 / 重生

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void spawnMonster(Monster monster)` / `public void spawnMonster(Monster monster, int difficulty, boolean isPq)` | 生成怪物（标准入口） | ① 容量满（PyPQ `mobCapacity`）拒绝；② `changeDifficulty`；③ `setMap` + 活动实例登记；④ `spawnAndAddRangedMapObject` 下发；⑤ `aggroUpdateController` + `updateBossSpawn`；⑥ CPQ 图按怪物 team 补挂红/蓝队已购 MCSkill buff；⑦ 定时掉落怪（护卫小浣猪/月妙÷3/冒牌泰勒斯/大雪人）注册 `monsterItemDrop`，其它 ID 打 error 日志；⑧ 计数 +1、登记自爆怪、`applyRemoveAfter` |
| `public void spawnRevives(Monster monster)` | 复活型生成（Boss 复活部件等） | 与 `spawnMonster` 类似但跳过难度/容量/CPQ/定时掉落逻辑 |
| `public void spawnMonsterOnGroundBelow(int id, int x, int y)` / `(Monster mob, Point pos)` | 落地生成 | `calcPointBelow(pos.y-1)` 找地面再 `spawnMonster` |
| `public void spawnCPQMonster(Monster mob, Point pos, int team)` | CPQ 生成 | 落地 + `setTeam` |
| `public void spawnFakeMonster(Monster monster)` / `public void spawnFakeMonsterOnGroundBelow(Monster mob, Point pos)` | 生成「假怪」（客户端可见不可打，如扎昆本体） | `setFake(true)`，用 `spawnFakeMonster` 包 |
| `public void makeMonsterReal(Monster monster)` | 假怪转真 | `setFake(false)` + 广播 + 仇恨更新（扎昆手臂打光后启用本体） |
| `public void spawnMonsterWithEffect(Monster monster, int effect, Point pos)` | 带出场特效生成 | 落地后 `setSpawnEffect(effect)` 再下发 |
| `public void spawnDojoMonster(Monster monster)` | 道场生成 | 在 3 个预设坐标中随机取点，effect=15 |
| `public void spawnHorntailOnGroundBelow(Point targetPoint)` | 暗黑龙王全套生成 | 先生成召唤用引导怪 `SUMMON_HORNTAIL`；生成 HT 主体（挂 listener 汇总伤害）与头 A→尾各部件（部件受击 `applyFakeDamage` 转嫁给主体、治疗反向），部件 parentMobOid 指向引导怪 |
| `public void damageMonster(Character chr, Monster monster, int damage)` | 玩家伤害怪物 | 扎昆一阶段（本体未实体化时手臂在场）直接 return 实现免伤；否则 `monster.damage`；触发自爆阈值（`selfDestruction.hp`）时按其 action 击杀；`killed` 则 `killMonster(...,true)` |
| `public void killMonster(Monster monster, Character chr, boolean withDrops)` / `(…, int animation)` | 击杀怪物 | `removeKilledMonsterObject` 成功后：无击杀者分支仅分发事件+广播；有击杀者分支：① 击杀高出自身 30 级怪触发 autoban 警告；② CPQ 图 `gainCP`；③ 怪物掉 buff 道具则全图存活玩家吃效果；④ 扎昆手臂全灭则 `makeMonsterReal(本体)`；⑤ `monster.killBy(chr)` 得掉落归属者并 `dropFromMonster`；⑥ Boss 血条持有者 `resetPlayerAggro`；finally 分发击杀事件+广播 kill 包（防异常导致内存泄漏） |
| `private boolean removeKilledMonsterObject(Monster monster)` | 从图中移除被杀怪 | 持怪锁：HP<0（已移除）返回 false；计数-1、`removeMapObject`、`disposeMapObject`、Boss 血条图清血条 |
| `public void killFriendlies(Monster mob)` | 杀友方怪 | 以图内第一个玩家为击杀者，不掉落 |
| `public void killMonster(int mobId)` / `public void killMonsterWithDrops(int mobId)` | 按 ID 全杀（掉/不掉落） | `killMonsterWithDrops` 优先以该怪最高伤害者为击杀者，无人则取图内任一玩家 |
| `public void softKillAllMonsters()` | 软清怪（不出死亡动画事件细节） | 关刷怪点后逐怪 `removeKilledMonsterObject` + `dispatchMonsterKilled(false)`，跳过友方怪 |
| `public void killAllMonsters()` / `public void killAllMonstersNotFriendly()` | 全杀 | 先 `closeMapSpawnPoints` 再逐怪 `killMonster(mob, null, false, 1)` |
| `private void applyRemoveAfter(Monster monster)` | 「到时自毁/消失」怪登记 | `removeAfter>0` 或自爆 hp<0：向 OverallService 注册延时 kill，任务压入 `monster.pushRemoveAfterAction` |
| `public void dismissRemoveAfter(Monster monster)` | 立即触发自毁 | `popRemoveAfterAction` 非空则 `forceRunOverallAction` |
| `public void addSelfDestructive(Monster mob)` / `public boolean removeSelfDestructive(int mapobjectid)` | 自爆怪登记/移除 | 怪物 stats 带 selfDestruction 才登记 |
| `private void monsterItemDrop(Monster m, long delay)` | 定时掉落 | 委托 `m.dropFromFriendlyMonster(delay)` |

### 2.8 刷怪点（SpawnPoint）管理与重生调度

> 刷怪点实体类为 `org.gms.server.life.SpawnPoint`（生命周期、mobTime 判定在彼处实现）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addMonsterSpawn(Monster monster, int mobTime, int team)` | 登记常规刷怪点 | 落地坐标建 `SpawnPoint` 放入 `monsterSpawn`；`shouldSpawn()` 或 `mobTime == -1`（一次性）立即强制刷一只 |
| `public void addAllMonsterSpawn(Monster monster, int mobTime, int team)` | 登记到全量列表 | 供活动图 `resetMapObjects` 重置后整批重刷 |
| `public void removeMonsterSpawn(int mobId, int x, int y)` / `public void removeAllMonsterSpawn(int mobId, int x, int y)` | 按 (怪ID, 落地点) 移除刷怪点 | `calcPointBelow(x, y-1)` 规整坐标后比对 |
| `private List<SpawnPoint> getMonsterSpawn()` / `getAllMonsterSpawn()` | 快照取列表 | 各自 `synchronized` 块复制 |
| `public void respawn()` | 常规周期重生（频道定时调用） | `allowSummons` 关闭或图内无人返回；`getNumShouldSpawn` 依配置 `use_enable_full_respawn`（满额）或按人数 spawn rate（`0.70+0.05*min(6,n)`）算缺口；洗牌刷怪点逐个 `shouldSpawn()` 补齐 |
| `public void instanceMapRespawn()` / `public void instanceMapForceRespawn()` | 活动实例补怪（普通/强制） | 缺口 = 全部刷怪点 − 当前存活；强制版用 `shouldForceSpawn` |
| `public void instanceMapFirstSpawn(int difficulty, boolean isPq)` | 活动图首刷 | 仅刷 `mobTime == -1` 的一次性怪 |
| `public void spawnAllMonstersFromMapSpawnList()` / `(int difficulty, boolean isPq)` | 按全量列表整批生成 | — |
| `public void spawnAllMonsterIdFromMapSpawnList(int id)` / `(int id, int difficulty, boolean isPq)` | 按怪 ID 整批生成 | 仅 `shouldForceSpawn` 的点 |
| `public void closeMapSpawnPoints()` / `public void restoreMapSpawnPoints()` | 全体禁刷/恢复 | 逐点 `setDenySpawn` |
| `public void setAllowSpawnPointInBox(boolean allow, Rectangle box)` / `setAllowSpawnPointInRange(boolean allow, Point from, double rangeSq)` | 区域禁刷/恢复 | 按坐标过滤 |
| `public SpawnPoint findClosestSpawnpoint(Point from)` | 最近刷怪点 | — |
| `public void mobMpRecovery()` | 全怪 MP 恢复 | 存活怪 `heal(0, level)`（频道定时调用） |
| `public void reportMonsterSpawnPoints(Character chr)` | GM 查看刷怪点报表 | 中文表格输出：总刷怪点/已刷数、每点 ID、可刷、现存、坐标、刷新间隔、阵营 |
| `private static double getCurrentSpawnRate(int numPlayers)` / `private int getNumShouldSpawn(int numPlayers)` | 重生率计算 | 见 `respawn` 行 |
| `public void allowSummonState(boolean b)` / `public boolean getSummonState()` | 重生总开关（`allowSummons`） | 事件控制「禁止刷怪」用 |

### 2.9 怪物/实体查询

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Monster getMonsterById(int id)` / `public Monster getMonsterByOid(int oid)` | 按 ID/OID 找怪 | OID 版先 `getMapObject` 再验类型 |
| `public int countMonster(int id)` / `public int countMonster(int minid, int maxid)` / `public int countMonsters()` / `public int countBosses()` | 怪物计数 | 全图范围查询过滤 |
| `public final List<MapObject> getMonsters()` / `public final List<Monster> getAllMonsters()` | 怪物列表（原始/强转） | — |
| `public boolean isHorntailDefeated()` | 暗黑龙王是否被讨伐 | DEAD_HORNTAIL 区间内所有部件怪都在场（尸体形态）即视为击败 |
| `public Reactor getReactorByOid(int oid)` / `getReactorById(int Id)` / `getReactorByName(String name)` / `getReactorsByIdRange(int first, int last)` | 反应堆查询 | — |
| `public final List<MapObject> getReactors()` / `public final List<Reactor> getAllReactors()` / `public int countReactors()` | 反应堆列表/计数 | — |
| `public NPC getNPCById(int id)` / `public boolean containsNPC(int npcid)` | NPC 查询 | — |
| `public void destroyNPC(int npcid)` | 移除 NPC | 广播 removeNPCController+removeNPC 后从表删（假定同图同 ID NPC 唯一） |
| `public void toggleHiddenNPC(int id)` | 显/隐 NPC | 翻转 `npc.setHide`，重新显示时广播 spawnNPC |

### 2.10 掉落系统（生成、过期、拾取、归属）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void toggleDrops()` | 掉落开关取反 | — |
| `private void dropFromMonster(Character chr, Monster mob, boolean useBaseRate)` | 怪物掉落主流程 | ① 掉落禁用（怪或图）返回；② droptype：爆炸奖励=3/FFA=2/有队=1/无队=0；③ chRate：非 Boss 用 dropRate、Boss 用 bossDropRate，叠加 SHOWDOWN 状态、家族 buff；`useBaseRate` 强制 1（友方怪）；④ 取全局掉落 + 生效掉落表（配置 `use_spawn_relevant_loot` 用怪身缓存否则查 `MonsterInformationProvider`）；⑤ `sortDropEntries` 分拣（普通/可见任务/他人任务）；⑥ `registerMobItemDrops` |
| `private static void sortDropEntries(List<MonsterDropEntry> from, …)` | 掉落表三分类 | 非任务物品→item；任务物品按 `chr.needQuestItem` 分到 visibleQuest / otherQuest |
| `private void registerMobItemDrops(…)` | 掉落执行调度 | 配置 `use_spawn_loot_on_animation` 开启：按怪 `die1` 动画时长×0.42 延迟入 `mobLootEntries`（lootLock）；否则直接 `mle.run()` |
| `private void spawnMobItemDrops()` | 延迟掉落泵（200ms 定时） | lootLock 内筛出到期条目并移除，锁外逐个 `run()` |
| `private byte dropItemsFromMonsterOnMap(List<MonsterDropEntry>, Point pos, byte d, float chRate, byte droptype, int mobpos, Character chr, Monster mob)` | 普通掉落表逐条掷骰 | 洗表后对每条：dropChance=chance×chRate×卡片率（封顶 int）；命中则 x 坐标按 d 左右交替散开（droptype=3 步长 40 否则 25）；itemId==0 为金币（区间随机、MESOUP buff、mesoRate，≤0 修正为 MAX_VALUE）→ `spawnMesoDrop`；装备走 `randomizeStats`，普通物按数量区间 → `spawnDrop` |
| `private byte dropGlobalItemsFromMonsterOnMap(List<MonsterGlobalDropEntry>, …)` | 全局掉落（印章等） | 同上，仅物品不掉金币 |
| `public void dropItemsFromMonster(List<MonsterDropEntry> list, Character chr, Monster mob)` | 指定列表必掉（脚本用） | chRate 固定 1000000，droptype 按是否有队 |
| `public void dropFromFriendlyMonster(Character chr, Monster mob)` | 友方怪掉落 | `dropFromMonster(…, useBaseRate=true)` |
| `public void dropFromReactor(Character chr, Reactor reactor, Item drop, Point dropPos, short questid)` | 反应堆掉落 | 经 `calcDropPos` 归位后 `spawnDrop` |
| `private void spawnDrop(Item idrop, Point dropPos, MapObject dropper, Character chr, byte droptype, short questid)` | 内部掉物注册 | 建 `MapItem`、设置 dropTime；`spawnAndAddRangedMapObject` 仅对 `needQuestItem` 的玩家下发；`instantiateItemDrop` + `activateItemReactors` |
| `public final void spawnMesoDrop(int meso, Point position, MapObject dropper, Character owner, boolean playerDrop, byte droptype)` | 掉金币 | 同上，MapItem 走 meso 构造 |
| `public final void spawnItemDrop(MapObject dropper, Character owner, Item item, Point pos, boolean ffaDrop, boolean playerDrop)` / `(…, byte dropType, boolean playerDrop)` | 玩家/脚本掉物（公开入口） | 图带 `DROP_LIMIT` 限制时改为「即现即灭」；否则注册 + 广播出现（mod=0）+ 反应堆激活 |
| `public final void spawnItemDropList(List<Integer> list[, int minCopies, int maxCopies][, …, boolean ffaDrop, boolean playerDrop], MapObject dropper, Character owner, Point pos)`（3 重载） | 列表批量掉物 | 洗列表后从 `pos.x - 12*n` 起逐个间隔 25 掉；ID 0 掉 10×mesoRate 金币；装备随机属性 |
| `public final void disappearingItemDrop(MapObject dropper, Character owner, Item item, Point pos)` / `disappearingMesoDrop(int meso, …)` | 即现即灭掉落（不注册） | 仅广播 mod=3 的掉落包 |
| `private void instantiateItemDrop(MapItem mdrop)` | 掉落登记+容量控制 | 地图掉落 ≥ `item_limit_onMap` 时循环从 `registeredDrops` 头部取弱引用清除最旧掉落直到有空位；注册过期时间并入弱引用队列；计数 +1 |
| `private void registerItemDrop(MapItem mdrop)` | 登记过期时间 | everlast 图 `Long.MAX_VALUE`，否则 now+`item_expire_time` |
| `private void unregisterItemDrop(MapItem mdrop)` | 注销掉落登记 | — |
| `private void makeDisappearExpiredItemDrops()` | 过期掉落清理（定时） | 读锁筛出过期项，逐个消失后写锁移除登记 |
| `public boolean makeDisappearItemFromMap(MapObject mapobj)` / `(MapItem mapitem)` | 使掉落消失 | MapItem 仍在图中且未拾取：持 itemLock 置 pickedUp 并走 `pickItemDrop`；非 MapItem 仅当 null 返回 true |
| `public void pickItemDrop(Packet pickupPacket, MapItem mdrop)` | 拾取落地（调用方需持 itemLock 且已查重） | 范围广播拾取包、计数-1、移除实体、置 pickedUp、注销登记 |
| `public int getDroppedItemCount()` / `public int getDroppedItemsCountById(int itemid)` | 掉落计数 | 后者按物品 ID 统计 |
| `public List<MapItem> updatePlayerItemDropsToParty(int partyid, int charid, List<Character> partyMembers, Character partyLeaver)` | 组队后掉落归属刷新 | 对每个掉落持 itemLock：本人掉落（ownerid==charid）→ `setPartyOwnerIdLocked(partyid)` 并对在图队友/离队者收集 remove+update 包（包在锁内构造、**锁外发送**避免持锁网络 IO）；其余 partyid 匹配的掉落收集返回（供新队员补看） |
| `public void updatePartyItemDropsToNewcomer(Character newcomer, List<MapItem> partyItems)` | 新队员补看队伍掉落 | 同样锁内构包锁外发 |
| `public void clearDrops()` / `public void clearDrops(Character player)` | 清空掉落 | 全图移除 ITEM 类实体并广播 removeItemFromMap（player 版带其 cid） |
| `private List<MapItem> getDroppedItems()` | 掉落快照 | — |

### 2.11 掉落坐标计算与地形查询

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private Point calcPointBelow(Point initial)` | 求某点正下方地面点 | `footholds.findBelow`；斜坡用三角函数（atan/acos）插值出 y；无地面返回 null |
| `public Point calcDropPos(Point initial, Point fallback)` | 计算合法掉落点 | x 夹到 `xLimits`；上移 85px 求 `calcPointBelow`；失败退二分搜索；结果必须落在 `mapArea` 内否则用 fallback |
| `private Point bsearchDropPos(Point initial, Point fallback)` | 二分找最近有效落点 | 以 fallback 方向为界二分收缩至间距 ≤5px；无解返回 fallback |
| `public void generateMapDropRangeCache()` | 生成/读取本图掉落 x 边界 | 静态 `bndLock` + `dropBoundsCache` 进程级缓存；未缓存时对地图左右边缘各做一次二分搜索，±14px 余量 |
| `public Point getGroundBelow(Point pos)` | 落地坐标（通用） | `pos.y-14` 下探（-14 修宠物落地问题）后 `calcPointBelow` 再 -1 |
| `public Point getPointBelow(Point pos)` | 直接下探 | — |
| `public boolean canDeployDoor(Point pos)` | 能否在此开门 | 下方地面存在且距地面 ≤42px |
| `private static double getAngle(Point doorPoint, Point spawnPoint)` | 门相对出生点角度 | y 轴取反映射到「3 点钟=0°，12 点钟=270°」坐标系 |
| `public static String getRoundedCoordinate(double angle)` | 角度→方位字母（E/SE/S/…） | `directions[round(angle%360/45)]` |
| `public Pair<String, Integer> getDoorPositionStatus(Point pos)` | 门位描述（偏离最近出生点） | 距离平方 ≤777777.7 视为正常返回 null；否则返回（方位, 距离），供 `use_enforce_mystic_door_position` 严格门位提示 |
| `public void setFootholds(FootholdTree)` / `public FootholdTree getFootholds()` | 地面碰撞树存取 | — |
| `public void addMapleArea(Rectangle rec)` / `public List<Rectangle> getAreas()` / `public Rectangle getArea(int index)` | WZ area 矩形 | 事件判定（`getNumPlayersInArea`）用 |
| `public final int getNumPlayersInArea(int index)` / `getNumPlayersInRect(Rectangle)` / `getNumPlayersItemsInArea(int)` / `getNumPlayersItemsInRect(Rectangle)` | 区域内玩家数（+掉落数） | — |

### 2.12 反应堆管理

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void spawnReactor(Reactor reactor)` | 生成反应堆 | `setMap(this)` 后限距注册下发 |
| `public void setReactorState()` | 全部反应堆推进到状态 1 | 对 state<1 者持锁 `resetReactorActions(1)` 并广播触发 |
| `public final void limitReactor(int rid, int num)` | 限制同 ID 反应堆数量 | 计数超出 num 的多余者销毁 |
| `public boolean isAllReactorState(int reactorId, int state)` | 该 ID 反应堆是否全处某状态 | — |
| `public void destroyReactors(int first, int last)` / `public void destroyReactor(int oid)` | 按 ID 段/OID 销毁 | `reactor.destroy()` 成功（可移除）才 `removeMapObject` |
| `public void resetReactors()` / `public final void resetReactors(List<Reactor> list)` | 重置全部/指定反应堆 | 有延迟重生任务的先 `forceDelayedRespawn()` 立即跑；否则持锁 `resetReactorActions(0)`+`setAlive(true)`+广播 |
| `public void shuffleReactors()` / `shuffleReactors(int first, int last)` / `shuffleReactors(List<Object> list)` | 打乱反应堆位置（全部/ID 段/指定集合） | 收集坐标 → 洗牌 → 重新分配 |

### 2.13 物品触发型反应堆联动

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private void activateItemReactors(MapItem drop, Client c)` | 新掉落是否喂给物品反应堆 | 遍历 type==100 反应堆：`getReactItem` 匹配物品 ID+数量且掉落落在 `getArea()` 内 → 5s 后跑 `ActivateItemReactor` |
| `public void searchItemReactors(Reactor react)` | 反应堆就位后反查已有掉落 | 对图中每个掉落持 itemLock 匹配（未拾取、ID/数量相符、在区域内、owner 在线）→ 注册 5s 延迟激活 |
| `private void registerMapSchedule(Runnable r, long delay)` | 地图级延时任务 | 走频道 `OverallService.registerOverallAction(mapid, …)`（按地图聚队的执行服务） |

### 2.14 传送门

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addPortal(Portal myPortal)` | 注册门 | 以门 ID 为 key（门 ID=0x80 起为动态门位） |
| `public Portal getPortal(int portalid)` / `public Portal getPortal(String portalname)` | 按 ID/名取门 | — |
| `public Portal getDoorPortal(int doorid)` | 取城镇门位门（0x80+slot） | 不存在时 warn 日志并回退 `portals.get(0x80)` |
| `public Portal getRandomPlayerSpawnpoint()` | 随机出生点 | 过滤 type∈[0,1] 且 targetMapId==NONE 的门随机取 |
| `public Portal findClosestTeleportPortal(Point from)` | 最近传送点（type==1 且有目标图） | — |
| `public Portal findClosestPlayerSpawnpoint(Point from)` | 最近出生点 | — |
| `public Portal findClosestPortal(Point from)` | 最近任意门 | — |
| `public Portal findMarketPortal()` | 找自由市场门 | 脚本名含 "market" |

### 2.15 生成其它实体（门/召唤/雾/风筝）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void spawnDoor(DoorObject door)` | 生成门 | SpawnCondition：仅 `chr.getMapId() == door.getFrom().getId()` 的玩家可见 |
| `public void spawnSummon(Summon summon)` | 生成召唤兽 | 限距注册 |
| `public void spawnMist(Mist mist, int duration, boolean poison, boolean fake, boolean recovery)` | 生成雾（毒雾/烟幕/恢复光环） | 注册+广播（fake 用 30 级假数据）；poison：每 2s（首延 2s 后 2.5s 周期）对雾内怪 `makeChanceResult` 上 POISON；recovery：对雾内本人/队友回 MP（X%×maxMp）；到期经 `MobMistService` 注销并广播消失 |
| `public void spawnKite(Kite kite)` | 生成风筝（留言） | 注册广播后经 `World.registerTimedMapObject` 到 `kite_expire_time` 自动消失 |
| `public void addPlayerPuppet(Character player)` / `public void removePlayerPuppet(Character player)` | 傀儡登场/退场通知全怪仇恨 | 逐怪 `aggroAddPuppet`/`aggroRemovePuppet` |
| `public MonsterAggroCoordinator getAggroCoordinator()` | 取仇恨协调器 | — |

### 2.16 环境与地图表现

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void changeEnvironment(String mapObj, int newState)` | 环境物件状态切换广播 | — |
| `public final void toggleEnvironment(String ms)` | 环境物件状态取反 | 已记录为 1→2，否则 1 |
| `public final void moveEnvironment(String ms, int type)` | 设置并广播环境状态 | 写入 `environment`（写锁） |
| `public final Map<String, Integer> getEnvironment()` | 环境状态只读视图 | — |
| `public void startMapEffect(String msg, int itemId)` / `(…, long time)` | 地图横幅效果 | 已有效果时忽略；广播 startData，到期经 OverallService 广播 destroy 并清空 |
| `public void sendNightEffect(Character chr)` | 给单玩家发夜间背景 | 对 `backgroundTypes` 中 type≥3 的层发 `changeBackgroundEffect` |
| `public void broadcastNightEffect()` | 全图夜间效果 | 迭代+断线剔除后逐人 `sendNightEffect` |
| `public void setBackgroundTypes(HashMap<Integer, Integer>)` | 背景层类型装配 | — |
| `public void setClock(boolean)` / `public boolean hasClock()` | 地图时钟 | — |
| `private int hasBoat()` / `public void setBoat(boolean)` / `public void setDocked(boolean)` / `public boolean getDocked()` | 船状态 | `hasBoat`：无船 0 / 停靠 1 / 离港 2 |
| `public void broadcastShip(boolean state)` / `public void broadcastEnemyShip(boolean state)` | 广播船/敌船（Crog）状态 | 广播后同步 `setDocked` |

### 2.17 内嵌 GM 活动 / 活动地图判定

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void startEvent(Character chr)` | 按地图启动对应 GM 活动 | 椰子图建 `Coconut`；健身图建 `Fitness`；Ola 4 图建 `Ola`；OX 图建 `OxQuiz` 并发题；雪球图建两队 `Snowball` |
| `public void startEvent()` / `public boolean eventStarted()` / `public void setEventStarted(boolean)` | 活动开始标记 | — |
| `public void setSnowball(int team, Snowball)` / `public Snowball getSnowball(int team)` | 雪球存取 | team 0/1 |
| `public void setCoconut(Coconut)` / `public Coconut getCoconut()` | 椰子活动存取 | — |
| `public String getEventNPC()` | 活动报名 NPC 提示（中文） | 南港/金银岛/天空/玩具城四图分别提示 NPC 名 |
| `public boolean hasEventNPC()` | 是否活动报名图 | — |
| `public boolean isStartingEventMap()` / `public boolean isEventMap()` | 活动起始图/活动图判定 | 按 MapId 区间 |
| `private boolean hasForcedEquip()` / `private boolean specialEquip()` | 强制装备图（fieldType 81/82）/特殊装备图（4/19） | — |
| `public void setTimeMob(int id, String msg)` / `public Pair<Integer, String> getTimeMob()` | 定时提示怪（timeMob） | — |

### 2.18 地图重置与生命周期

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void clearMapObjects()` | 清空图内容 | `clearDrops` + `killAllMonsters` + `resetReactors` |
| `public final void resetFully()` | 完全重置 | `resetMapObjects()` |
| `public void resetMapObjects()` / `public void resetMapObjects(int difficulty, boolean isPq)` | 重置并按全量列表重刷 | 清内容 → `restoreMapSpawnPoints` → `instanceMapFirstSpawn` |
| `public void resetPQ()` / `public void resetPQ(int difficulty)` | PQ 重置 | `resetMapObjects(difficulty, true)` |
| `private void startItemMonitor()` | 空图监控启动（首人进图时） | 注册 4 个定时任务：① itemMonitor（`item_monitor_time` 周期）：图内无人连续两周期则停全部监控并停 aggro 协调器；`registeredDrops` >70 时清无效弱引用；② expireItemsTask（`item_expire_check`）掉落过期；③ 配置 `use_spawn_loot_on_animation` 时 mobSpawnLootTask（200ms）延迟掉落泵；④ characterStatUpdateTask（200ms）属性批量更新 |
| `private void stopItemMonitor()` | 停全部 4 任务 | — |
| `private boolean hasItemMonitor()` | 监控是否在跑 | — |
| `private void cleanItemMonitor()` | 清理 `registeredDrops` 中已回收的弱引用 | `removeAll(Collections.singleton(null))` |
| `public void runCharacterStatUpdate()` | 批量执行属性更新任务 | 交换缓冲后在锁外逐个 run |
| `public void registerCharacterStatUpdate(Runnable r)` | 登记属性更新任务 | 由各 handler 投递，200ms 内合并刷出 |
| `public void dispose()` | 销毁地图 | 全怪 `dispose`；`clearMapObjects`；清 event/footholds/portals/mapEffect；停 aggro 协调器与 4 个定时任务 |

### 2.19 地图所有权（防抢图）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean claimOwnership(Character chr)` | 占图 | 无主时登记 owner、`chr.setOwnedMap`、刷新活跃时间并向频道注册；已占者本人重复 claim 返回 true |
| `public Character unclaimOwnership()` / `public boolean unclaimOwnership(Character chr)` | 释放所有权（任意/指定） | 释放后活跃时间置 MAX，向频道注销 |
| `private void refreshOwnership()` | 刷新活跃时间 | — |
| `public boolean isOwnershipRestricted(Character chr)` | 是否被限制（他人占图） | owner 非本人且非其队友 → `chr.showMapOwnershipInfo(owner)` 并返回 true；否则刷新活跃时间 |
| `public void checkMapOwnerActivity()` | 活跃度检查（频道定时） | 超 60s 无活动则释放并全图中文广播「这里现在是无主之地了」 |

### 2.20 CPQ（怪物嘉年华）专属

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean isCPQMap()` / `isCPQMap2()` / `isCPQLobby()` / `isBlueCPQMap()` / `isPurpleCPQMap()` / `isCPQWinnerMap()` / `isCPQLoserMap()` | 按 mapid 判定 CPQ 场地类型 | 980000x01/980031x00 系列为战场、980000x00 为大厅等 |
| `public Point getRandomSP(int team)` | 取一个未占用 CPQ 出怪点 | 已占用（`takenSpawns`）或阵营不符的跳过；蓝图（isBlueCPQMap）不分校验阵营 |
| `public GuardianSpawnPoint getRandomGuardianSpawn(int team)` | 随机守卫塔点位 | 全占用返回 null；随机轮询（30% 命中率）且阵营匹配（-1 通用） |
| `public void addGuardianSpawnPoint(GuardianSpawnPoint a)` | 登记守卫点位（WZ guardianGenPos） | — |
| `public int spawnGuardian(int team, int num)` | 召唤守卫塔（CPQ 技能） | 队伍 buff 已满 4 个返回 2、已有该技能返回 0、无空点位 -1；否则以 `9980000+team` 建 Reactor、落位、命名 `team+num`、生成并 `buffMonsters`，最后立即 hit 一次激活 |
| `public void buffMonsters(int team, MCSkill skill)` | 给己方怪上 CPK buff | 记入红/蓝 buff 列表并对 `mob.getTeam()==team` 的怪 applyEffect；后续新生怪在 `spawnMonster` 中补挂 |
| `public List<MCSkill> getBlueTeamBuffs()` / `getRedTeamBuffs()` / `public void clearBuffList()` | 队伍 buff 存取/清空 | — |
| `public final List<Integer> getSkillIds()` / `public final void addSkillId(int z)` | CPQ 可用技能 ID | — |
| `public final void addMobSpawn(int mobId, int spendCP)` / `public final List<Pair<Integer, Integer>> getMobsToSpawn()` | CPQ 花费 CP 召怪的表 | — |
| `public int getMaxMobs()`/`setMaxMobs(int)`、`getMaxReactors()`/`setMaxReactors(int)`、`getDeathCP()`/`setDeathCP(int)`、`getTimeDefault()`/`setTimeDefault(int)`、`getTimeExpand()`/`setTimeExpand(int)` | CPQ 参数存取 | 由 MapFactory 从 WZ monsterCarnival 节点装配 |

### 2.21 内部类与函数式接口

| 类型 | 说明 |
| --- | --- |
| `private class MobLootEntry implements Runnable` | 封装一次怪物掉落的全部参数（droptype/mobpos/chRate/pos/三类掉落表/全局表/chr/mob）；`run()` 依次执行：普通掉落 → 全局掉落 → 可见任务掉落 → 他人任务掉落（共享散开计数 d）。供「死亡动画后掉落」延迟执行或立即执行 |
| `private class ActivateItemReactor implements Runnable` | 物品反应堆激活任务：持反应堆 hitLock；校验 type==100、可收集、掉落仍在图中；持 itemLock 置掉落 pickedUp 并注销/移除/广播；`reactor.setShouldCollect(false)` 后 `hitReactor(c)`；带 delay 的反应堆再注册 OverallService 延时重置（复位状态 0、复活、广播触发） |
| `private interface DelayedPacketCreation` | `void sendPackets(Client c)`——对象生成时对每个视野内玩家的延迟发包回调 |
| `private interface SpawnCondition` | `boolean canSpawn(Character chr)`——生成对象时按玩家过滤可见性 |

---

## 3. 地图加载与管理

### 3.1 MapFactory

**静态工具工厂**：从 WZ（`Map.wz`/`String.wz`）加载地图定义并装配出完整 `MapleMap`，本仓库相对 OdinMS 已将其改为全静态方法（无实例状态，缓存职责移至 `MapManager`）（源码路径：`server/maps/MapFactory.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `nameData` | `static final Data` | String.wz 的 `Map.img`（地名/街名） |
| `mapSource` | `static final DataProvider` | Map.wz 数据源 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static MapleMap loadMapFromWz(int mapid, int world, int channel, EventInstanceManager event)` | **核心装配**：加载一张图 | ① `getMapName` 拼 `Map/Map{区号}/9位ID.img`，读 info；② `link` 节点跳转（道场等复用图）；③ 建 `MapleMap`（returnMap、mobRate）并挂活动实例；④ 装配 onFirstUserEnter/onUserEnter/fieldLimit/createMobInterval；⑤ PortalFactory 逐门装配；⑥ timeMob；⑦ 边界：VRTop==VRBottom 的旧式图用 miniMap center 反推（无 miniMap 用 2^18 兜底），否则 VR 四边；⑧ 逐 foothold 节点建 `Foothold`（含 prev/next），累计包围盒建 `FootholdTree`；⑨ `area` 矩形、`seat` 数量；⑩ 非活动图时 `PlayerNPC.addPlayerNPCMapObject`；⑪ `loadLifeFromWz`+`loadLifeFromDb`；⑫ CPQ 图读 monsterCarnival 节点（deathCP/mobGenMax/timeDefault/timeExpand/guardianGenMax/guardianGenPos/skill/mob 表）；⑬ reactor 节点逐个 `loadReactor` 并 spawn；⑭ 地名/街名、clock、everlast、town、decHP、protectItem、forcedReturn、shipObj、timeLimit、fieldType、fixedMobCapacity、recovery、back 层类型；⑮ `generateMapDropRangeCache` |
| `private static void loadLifeFromWz(MapleMap map, Data mapData)` | 从 WZ life 节点加载生物 | 逐条读 id/type/team 等；CPQ2 图按 life 序号奇偶分配 team；委托 `loadLifeRaw` |
| `private static void loadLifeFromDb(MapleMap map)` | 从 `plife` 表加载玩家放置的生物 | 按 map+world 查询，字段同 WZ（mobtime 等），委托 `loadLifeRaw` |
| `private static void loadLifeRaw(MapleMap map, int id, String type, int cy, int f, int fh, int rx0, int rx1, int x, int y, int hide, int mobTime, int team)` | 生物落地分流 | `LifeFactory.getLife` 取模板并回填坐标；怪物分支：读 `mob_respawn_rate`（<1 或事件图置 1）与 `boss_respawn_mob_time_rate`（限定 0~1，Boss 专属缩短 mobTime）；循环 mobRespawnRate 次注册（mobTime==-1 强制 spawn 一次否则 `addMonsterSpawn`）；同时 `addAllMonsterSpawn` 供整图重置；非怪物（NPC）直接 `addMapObject` |
| `private static AbstractLoadedLife loadLife(int id, String type, …, int hide)` | 建生物对象 | `LifeFactory.getLife` + 回填 cy/f/fh/rx0/rx1/pos，hide==1 隐藏 |
| `private static Reactor loadReactor(Data reactor, String id, byte facingDirection)` | 建反应堆 | `ReactorFactory.getReactor` 模板 + 位置/朝向/延迟（reactorTime 秒→ms）/名称，`resetReactorActions(0)` |
| `private static String getMapName(int mapid)` | 拼 img 路径 | `Map/Map{mapid/100000000}/{补零 9 位}.img` |
| `private static String getMapStringName(int mapid)` | 拼 String.wz 路径 | 按 mapid 区间映射 maple/victoria/ossyria/elin/singapore/MasteriaGL/Episode1GL/weddingGL/HalloweenGL/event/jp/etc |
| `public static String loadPlaceName(int mapid)` / `loadStreetName(int mapid)` | 地名/街名 | 异常时返回空串 |
| `public static String getMapIdByLifeId(int lifeId)` | 反查包含某生物的地图 ID | 从 Map.wz 根递归（`resolveDir`→`resolveFile`→`resolvePath`）遍历每个 img 的 life 节点比对 id |
| `private static String resolveDir(DataEntry, int)` / `resolveFile(DataEntity, int)` / `resolvePath(DataEntity, StringBuilder)` | 反查递归辅助 | 目录只下钻名字以 "Map" 开头的子目录；文件按路径取 Data 后查 life；resolvePath 回溯拼父路径 |

### 3.2 MapManager

频道（或活动实例）级地图缓存与访问入口：持 `world/channel/event` 三元组与缓存表 `maps`，用读写锁 + `synchronized loadMapFromWz` 保证并发下单一加载（源码路径：`server/maps/MapManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `channel` / `world` / `event` | `int`/`EventInstanceManager` | 归属上下文（event 非 null 时为活动专属实例的地图管理器） |
| `maps` | `Map<Integer, MapleMap>` | 地图缓存 |
| `mapsRLock`/`mapsWLock` | `Lock` | 缓存读写锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapManager(EventInstanceManager eim, int world, int channel)` | 构造 | 初始化读写锁 |
| `public MapleMap getMap(int mapid)` | 取图（懒加载+缓存） | 缓存命中直接返回，否则 `loadMapFromWz(mapid, true)` |
| `private synchronized MapleMap loadMapFromWz(int mapid, boolean cache)` | 加载（可不入缓存） | cache 时先查缓存；`MapFactory.loadMapFromWz(mapid, world, channel, event)` 装配；cache 时写回 |
| `public MapleMap resetMap(int mapid)` | 重置地图实例 | 写锁移除缓存后重新 `getMap`（用于事件重开等需要全新地图状态的场景） |
| `public MapleMap getMapByLifeId(int lifeId)` | 按生物 ID 反查图 | `MapFactory.getMapIdByLifeId` 后 getMap |
| `public MapleMap getDisposableMap(int mapid)` | 一次性地图（不缓存） | `loadMapFromWz(mapid, false)` |
| `public boolean isMapLoaded(int mapId)` | 是否已缓存 | — |
| `public Map<Integer, MapleMap> getMaps()` | 缓存快照 | — |
| `public void updateMaps()` | 周期驱动全部地图 | 每图 `respawn()` + `mobMpRecovery()` |
| `public void dispose()` | 销毁管理器 | 每图 `dispose()`，清 event 引用 |

### 3.3 MapMonitor

地图监视器：定时检查无人图并复位（活动脚本用）（源码路径：`server/maps/MapMonitor.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `monitorSchedule` | `ScheduledFuture<?>` | 5s 周期检查任务 |
| `map` / `portal` | `MapleMap`/`Portal` | 被监视地图与需重开的门 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapMonitor(MapleMap map, String portal)` | 启动监视 | 注册 5s 任务：图内无人 → `cancelAction` |
| `private void cancelAction()` | 复位地图 | 取消任务；`killAllMonsters` + `clearDrops` + 门 `setPortalStatus(OPEN)` + `resetReactors`；置空引用防重复 |

---

## 4. 传送门体系

### 4.1 Portal（接口）

传送门抽象：常量定义门类型与开关（源码路径：`server/maps/Portal.java`）。

| 常量/方法 | 说明 |
| --- | --- |
| `TELEPORT_PORTAL = 1` / `MAP_PORTAL = 2` / `DOOR_PORTAL = 6` | 门类型：传送点 / 普通换图门 / 魔法门动态门位 |
| `OPEN = true` / `CLOSED = false` | 门可用状态 |
| `int getType()` / `int getId()` / `Point getPosition()` / `String getName()` / `String getTarget()` / `String getScriptName()` | 门类型 / 门 ID / 坐标 / 名称 / 目标门名 / 脚本名 |
| `void setScriptName(String)` | 设置脚本名（同时初始化脚本锁） |
| `void setPortalStatus(boolean)` / `boolean getPortalStatus()` | 门可用开关 |
| `int getTargetMapId()` | 目标地图 ID |
| `void enterPortal(Client c)` | 玩家进门逻辑 |
| `void setPortalState(boolean)` / `boolean getPortalState()` | 门状态（事件用另一标志位） |

### 4.2 GenericPortal

通用传送门实现：支持脚本门与直连门两种进门方式（源码路径：`server/maps/GenericPortal.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name`/`target`/`position`/`targetmap`/`type`/`id`/`scriptName` | — | 门名 / 目标门名 / 坐标 / 目标图 / 类型 / ID / 脚本名 |
| `status` | `boolean`（默认 true） | 门可用状态 |
| `portalState` | `boolean` | 事件用状态位 |
| `scriptLock` | `Lock`（公平锁，懒创建） | 脚本门串行化执行锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public GenericPortal(int type)` | 构造 | — |
| `public void enterPortal(Client c)` | **进门主逻辑** | ① 有脚本名：持 `scriptLock` 执行 `PortalScriptManager.executePortalScript`（脚本返回是否成功换图）；② 无脚本且目标图有效：黑板开启者禁入自由市场（中文提示）；目标图取活动实例版或频道版；目标门缺失回退 0 号门；**同图传送时记录 `setPetLootTeleportBeforePos` 补偿坐标**；`changeMap(to, pto)`；③ 未换图成功统一补发 `enableActions` |
| `public void setScriptName(String scriptName)` | 设脚本名 | 非空时懒建公平 `ReentrantLock`，置空时清锁 |
| 其余 getter/setter | 字段存取 | `getId/setId`、`getName/setName`、`getPosition/setPosition`、`getTarget/setTarget`、`getTargetMapId/setTargetMapId`、`getType`、`getPortalStatus/setPortalStatus`、`getPortalState/setPortalState` |

### 4.3 MapPortal

普通换图门（type 固定 `MAP_PORTAL=2`），行为全部继承 `GenericPortal`；`PortalFactory` 按 WZ `pt==2` 选用（源码路径：`server/maps/MapPortal.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapPortal()` | 构造 | `super(Portal.MAP_PORTAL)` |

### 4.4 PortalFactory

传送门装配工厂：从 WZ portal 节点构建 `Portal` 对象；每个 `MapFactory.loadMapFromWz` 调用各建一个实例（源码路径：`server/maps/PortalFactory.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `nextDoorPortal` | `int`（初值 0x80） | 动态门位（魔法门）ID 发号器，0x80 起递增 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Portal makePortal(int type, Data portal)` | 建门 | type==MAP_PORTAL 用 `MapPortal`，否则 `GenericPortal(type)`；`loadPortal` 填充 |
| `private void loadPortal(GenericPortal myPortal, Data portal)` | 填充门数据 | 读 pn/tn/tm/x/y/script（空串归 null）；DOOR_PORTAL 类型分配 `nextDoorPortal++` 作 ID，其余以节点名数字为 ID |

---

## 5. 地形与限制

### 5.1 Foothold

单条地面/墙壁段（WZ foothold 节点），实现 `Comparable` 供按高度排序（源码路径：`server/maps/Foothold.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `p1` / `p2` | `Point`（final） | 线段两端 |
| `id` | `int`（final） | foothold 节点 ID |
| `next` / `prev` | `int` | 相邻 foothold ID（平台连通性） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Foothold(Point p1, Point p2, int id)` | 构造 | — |
| `public boolean isWall()` | 是否墙壁 | `p1.x == p2.x`（垂直线） |
| `public int getX1()/getX2()/getY1()/getY2()` | 端点坐标 | — |
| `public int calculateFooting(int x)` | 求 x 处地面 y | 水平段直接返回 y；否则整数斜率/截距线性插值（精度有限） |
| `public int compareTo(Foothold o)` | 按高度排序 | 整体在对方上方返回 -1，下方返回 1，否则 0 |
| `public int getId()` / `getNext()`/`setNext(int)` / `getPrev()`/`setPrev(int)` | ID 与邻接存取 | — |

### 5.2 FootholdTree

四叉树（QuadTree）组织的 foothold 索引：空间加速「找某点下方地面 / 找墙」查询（源码路径：`server/maps/FootholdTree.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `nw/ne/sw/se` | `FootholdTree` | 四象限子树（懒创建） |
| `footholds` | `List<Foothold>` | 本节点容纳的段 |
| `p1`/`p2`/`center` | `Point` | 节点包围盒与中心 |
| `depth` / `maxDepth=8` | `int` | 当前/最大深度 |
| `maxDropX`/`minDropX` | `int` | 根节点统计的可掉落 x 边界 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public FootholdTree(Point p1, Point p2)` / `(Point p1, Point p2, int depth)` | 构造 | center 取包围盒一半 |
| `public void insert(Foothold f)` | 插入段 | 根节点顺带维护 min/maxDropX；深度达 8 或段完全在盒内则收容；否则懒建四子并按段端点与 center 的关系下放（都不满足进 se） |
| `private List<Foothold> getRelevants(Point p[, List])` | 收集某点相关象限的所有段 | 自顶向下把沿途节点段并入 |
| `public Foothold findWall(Point p1, Point p2)` | 找两点水平线上的墙 | 要求两点 y 相等（否则 `IllegalArgumentException`）；`findWallR` 递归 |
| `private Foothold findWallR(Point p1, Point p2)` | 找墙递归 | 命中条件：isWall 且 x 覆盖 p1.x~p2.x 且 y 覆盖 p1.y；按象限剪枝 |
| `public Foothold findBelow(Point p)` | **找某点正下方最近地面** | 取相关段中 x 覆盖 p.x 的非墙壁段，按高度排序（compareTo）；水平段 `y1>=p.y` 即命中；斜坡段用三角函数算出该 x 的 y（`cos(α)·(dx/cos(β))` 修正）后比较 |
| `public int getX1()/getX2()/getY1()/getY2()` | 包围盒 | — |
| `public int getMaxDropX()` / `getMinDropX()` | 掉落 x 边界 | — |

### 5.3 FieldLimit（枚举）

地图行为限制位掩码（WZ info/fieldLimit）（源码路径：`server/maps/FieldLimit.java`）。

| 枚举值 | 掩码 | 含义 |
| --- | --- | --- |
| `JUMP` | 0x01 | 禁跳跃 |
| `MOVEMENTSKILLS` | 0x02 | 禁位移技能（Rush 等） |
| `SUMMON` | 0x04 | 禁召唤 |
| `DOOR` | 0x08 | 禁开门 |
| `CANNOTMIGRATE` | 0x10 | 禁换频/回城卷/进商城等迁移 |
| `CANNOTVIPROCK` | 0x40 | 禁 VIP 岩石传送 |
| `CANNOTMINIGAME` | 0x80 | 禁小游戏 |
| `CANNOTUSEMOUNTS` | 0x200 | 禁坐骑 |
| `CANNOTUSEPOTION` | 0x1000 | 禁药水 |
| `CANNOTJUMPDOWN` | 0x20000 | 禁下跳 |
| `NO_EXP_DECREASE` | 0x80000 | 死亡不掉经验 |
| `DROP_LIMIT` | 0x400000 | 禁掉落物品（掉落即消失） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public long getValue()` | 取掩码值 | — |
| `public boolean check(int fieldlimit)` | 判断地图是否带此限制 | `(fieldlimit & i) == i` |

### 5.4 SavedLocation / SavedLocationType

`SavedLocation`：不可变的「地图+门」坐标对，供 `saveLocation`/`getSavedLocation` 回跳（源码路径：`server/maps/SavedLocation.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public SavedLocation(int mapId, int portal)` | 构造 | — |
| `public int getMapId()` / `public int getPortal()` | 取值 | — |

`SavedLocationType`（枚举）：存档位置类别——`FREE_MARKET`（自由市场）、`WORLDTOUR`、`FLORINA`、`INTRO`（新手引导）、`SUNDAY_MARKET`、`MIRROR`、`EVENT`、`BOSSPQ`、`HAPPYVILLE`、`MONSTER_CARNIVAL`、`DEVELOPER`、`JAIL`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static SavedLocationType fromString(String Str)` | 字符串转枚举 | `valueOf` |

---

## 6. 掉落物实体

### 6.1 MapItem

地上的一件掉落物（物品或金币）：携带归属（个人/队伍）、拾取类型、掉落时间与自身互斥锁（源码路径：`server/maps/MapItem.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `item` / `meso` | `Item`/`int` | 掉落物（item==null 表示纯金币） |
| `dropper` | `MapObject` | 掉落来源（怪/NPC/玩家） |
| `character_ownerid` / `party_ownerid` | `int` | 归属玩家 cid / 队伍 id（构造后 owner 可变，party 可变） |
| `questid` | `int`（-1） | 任务物品关联任务 |
| `type` | `byte` | 掉落类型：0 普通 / 1 组队 / 2 FFA / 3 爆炸奖励 |
| `pickedUp` / `playerDrop` / `partyDrop` | `boolean` | 已拾取 / 玩家丢弃 / 队伍掉落 |
| `dropTime` | `long` | 掉落时刻（归属 15s 保护期计时） |
| `itemLock` | `ReentrantLock` | 保护 owner 字段与拾取判定的实例锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapItem(Item item, Point position, MapObject dropper, Character owner, Client ownerClient, byte type, boolean playerDrop[, int questid])` | 物品构造（2 个重载） | 记录归属与队伍（`party_ownerid != -1` 即 partyDrop） |
| `public MapItem(int meso, Point position, MapObject dropper, Character owner, Client ownerClient, byte type, boolean playerDrop)` | 金币构造 | item 置 null |
| `public final Item getItem()` / `public final int getMeso()` / `public final MapObject getDropper()` | 基础取值 | — |
| `public final int getItemId()` | 统一物品标识 | meso>0 返回金币数否则物品 ID |
| `public final int getQuest()` | 关联任务 ID | — |
| `public final int getOwnerId()` / `getOwnerIdLocked()` / `getPartyOwnerId()` / `getPartyOwnerIdLocked()` / `setPartyOwnerId(int)` / `setPartyOwnerIdLocked(int)` | 归属读写（普通版/持锁版） | Locked 后缀版本要求调用方已持 `itemLock`（字段在构造后 party 部分可变，持锁路径读写保持锁纪律）；注释明确 `character_ownerid` 不可变但保留锁定读以统一锁纪律 |
| `public final int getClientsideOwnerId()` | 客户端展示归属 | 无队返回 cid 否则 party id |
| `public final boolean hasClientsideOwnership(Character player)` | 玩家是否「看得到归属」 | 本人 / 同队 / 已过保护期 |
| `public final boolean isFFADrop()` | 是否自由拾取 | type 2/3 或已过 15s 保护期 |
| `public final boolean hasExpiredOwnershipTime()` | 归属保护是否过期 | 掉落后 ≥15s |
| `public final boolean canBePickedBy(Character chr)` | **拾取判定（须持 itemLock 调用）** | 无主或 FFA → true；无队掉落：本人或队友（队友拾取时把 `party_ownerid` 提升为拾取者队伍）→ true；有队掉落：同队或本人（同样提升）→ true；否则看保护期 |
| `public final Client getOwnerClient()` | 归属者在线客户端 | 已登录且未离线（`isAwayFromWorld`）才返回，否则 null（供物品反应堆找到触发者） |
| `public final boolean isPlayerDrop()` / `isPickedUp()` / `setPickedUp(boolean)` / `getDropTime()` / `setDropTime(long)` / `getDropType()` | 状态存取 | — |
| `public void lockItem()` / `public void unlockItem()` | 实例锁 | 供外部（拾取 handler、地图归属刷新）加锁后再判定 |
| `public final MapObjectType getType()` | 类型 | `ITEM` |
| `public void sendSpawnData(Client client)` | 进视野下发 | 仅当玩家 `needQuestItem(questid, itemId)`（任务掉落按需可见）才持锁发 drop 包（mod=2） |
| `public void sendDestroyData(Client client)` | 消失下发 | removeItemFromMap |

### 6.2 ReactorDropEntry

反应堆掉落条目（public 字段 POJO）：`itemId/chance/questid` 三元组加 `assignedRangeStart/assignedRangeLength`（按几率分配的随机区间），供反应堆脚本掷骰（源码路径：`server/maps/ReactorDropEntry.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public ReactorDropEntry(int itemId, int chance, int questId)` | 构造 | 直接赋值三个 public 字段 |

---

## 7. 反应堆体系

### 7.1 Reactor

运行中的反应堆实例：状态机驱动（state/evstate），支持击打推进、物品投喂（type 100）、超时推进、延迟重生（源码路径：`server/maps/Reactor.java`，含中文注释）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rid` / `stats` | `int`/`ReactorStats`（final） | 反应堆模板 ID 与静态状态机 |
| `state` / `evstate` | `byte` | 当前状态 / 事件子状态 |
| `delay` | `int` | 触发后重置延迟（ms） |
| `map` | `MapleMap` | 所属地图 |
| `name` | `String` | 名称（脚本定位用） |
| `alive` / `shouldCollect` / `attackHit` | `boolean` | 存活 / 可收集投喂物 / 最近一次是否被攻击触发 |
| `timeoutTask` / `delayedRespawnRun` | `ScheduledFuture<?>`/`Runnable` | 状态超时任务 / 延迟重生任务 |
| `guardian` | `GuardianSpawnPoint` | CPQ 守卫塔占用的点位 |
| `facingDirection` | `byte` | 朝向 |
| `reactorLock` / `hitLock` | `ReentrantLock(true)` | 状态锁 / 击打串行锁（击打时先 hit 后 reactor 双锁） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Reactor(ReactorStats stats, int rid)` | 构造 | alive=true |
| `public void setState(byte)`/`getState()`、`setEventState(byte)`/`getEventState()`、`getStats()`、`getId()`、`setDelay(int)`/`getDelay()`、`setMap(MapleMap)`/`getMap()`、`getName()`/`setName(String)`、`getGuardian()`/`setGuardian(GuardianSpawnPoint)`、`setFacingDirection(byte)`/`getFacingDirection()`、`setShouldCollect(boolean)`/`getShouldCollect()` | 基础存取 | — |
| `public void lockReactor()` / `unlockReactor()` | 状态锁 | — |
| `public void hitLockReactor()` / `hitUnlockReactor()` | 击打锁 | 依次加/解 hitLock 与 reactorLock（顺序相反解锁） |
| `public int getReactorType()` | 当前状态触发类型 | `stats.getType(state)`（100=等投物品） |
| `public Pair<Integer, Integer> getReactItem(byte index)` | 当前状态需要的投喂物 | （itemId, 数量） |
| `public boolean isAlive()` / `setAlive(boolean)` / `public boolean isActive()` | 存活 / 可交互 | `isActive = alive && 类型有效` |
| `public boolean isRecentHitFromAttack()` | 最近是否被攻击触发 | attackHit 标志 |
| `public void resetReactorActions(int newState)` | 重置到某状态 | 设状态、取消超时、可收集、刷新超时；图非空则 `map.searchItemReactors(this)` 反查可投喂掉落 |
| `public void forceHitReactor(byte newState)` | 强制推进（脚本） | 持锁重置 + 广播 triggerReactor |
| `private void tryForceHitReactor(byte newState)` | tryLock 版推进 | 超时任务回调用，拿不到锁直接放弃 |
| `public void cancelReactorTimeout()` | 取消状态超时任务 | — |
| `private void refreshReactorTimeout()` | 安装状态超时 | `stats.getTimeout(state)>-1` 时调度到期 `tryForceHitReactor(超时后状态)` |
| `public void delayedHitReactor(Client c, long delay)` | 延迟击打 | TimerManager 调度 `hitReactor(c)` |
| `public void hitReactor(Client c)` / `public void hitReactor(boolean wHit, int charPos, short stance, int skillid, Client c)` | **击打主逻辑** | tryLock hitLock + reactorLock 双锁：非 active 返回；取消超时、记 attackHit；GM debug 输出中文调试信息；先跑 `ReactorScriptManager.onHit`；类型有效（<999 且 ≠-1）分支：type==2（沼泽植物类仅右侧可击）且姿态 0/2 跳过；遍历状态表按 activeSkills 过滤技能命中 → 推进 state；`nextState < state` 判定进入终态：reactorType<100 走 `map.destroyReactor`（延迟>0）或广播触发，随后 `ReactorScriptManager.act`；未终态：广播触发，循环反应器（state==nextState）补跑 act；刷新可收集与超时，type==100 时 `map.searchItemReactors`；类型无效分支：state++ 广播触发（9980000/9980001 守卫塔不跑 act） |
| `public boolean destroy()` | 销毁（含延迟重生登记） | tryLock：存活则置死、取消超时，delay>0 时 `delayedRespawn`，返回是否可从地图移除；已死且不在延迟重生中返回 true；最后广播销毁包 |
| `private void respawn()` | 重生 | 持锁复位状态 0 + 置活，广播 spawn |
| `public void delayedRespawn()` | 登记延迟重生 | runnable 存 `delayedRespawnRun`，经 OverallService 按地图注册 |
| `public boolean forceDelayedRespawn()` | 立即执行延迟重生 | 任务存在则 `forceRunOverallAction` 并返回 true |
| `public boolean inDelayedRespawn()` | 是否处于延迟重生等待 | — |
| `public Rectangle getArea()` | 交互判定矩形 | 位置 + stats 的 TL/BR 偏移 |
| `@Override sendDestroyData(Client)` / `sendSpawnData(Client)` / `makeDestroyData()` / `makeSpawnData()` | 数据包 | 存活才发 spawn；destroyReactor/spawnReactor 包 |

### 7.2 ReactorStats

反应堆静态状态机模板：状态号 → 一组 `StateData`（触发类型/投喂物/激活技能/下一状态）+ 状态超时表；进程级由 `ReactorFactory` 缓存（源码路径：`server/maps/ReactorStats.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `tl` / `br` | `Point` | 交互区左上/右下偏移 |
| `stateInfo` | `Map<Byte, List<StateData>>` | 状态 → 转移列表 |
| `timeoutInfo` | `Map<Byte, Integer>` | 状态 → 超时（ms），>−1 才登记 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void setTL(Point)`/`getTL()`、`setBR(Point)`/`getBR()` | 交互区存取 | — |
| `public void addState(byte state, List<StateData> data, int timeOut)` | 登记状态（列表版，主加载路径） | `stateInfo.put`；timeOut>-1 才入 timeoutInfo |
| `public void addState(byte state, int type, Pair<Integer,Integer> reactItem, byte nextState, int timeOut, byte canTouch)` | 登记状态（单转移版，getReactorS 简化加载用） | 包装成单元素 List（**不登记 timeout**） |
| `public int getTimeout(byte state)` | 状态超时 | 缺省 -1 |
| `public byte getTimeoutState(byte state)` | 超时后应转移的状态 | 取该状态最后一个转移的 nextState |
| `public byte getStateSize(byte state)` | 状态转移数 | — |
| `public byte getNextState(byte state, byte index)` | 第 index 个转移的目标状态 | 越界返回 -1 |
| `public List<Integer> getActiveSkills(byte state, byte index)` | 该转移要求的技能 ID 列表 | 无则 null |
| `public int getType(byte state)` | 状态触发类型 | 列表首元素 type，无状态返回 -1 |
| `public Pair<Integer, Integer> getReactItem(byte state, byte index)` | 该转移投喂物（ID, 数量） | — |

内部类 `public static class StateData`：不可变转移数据（`type`、`reactItem`、`activeSkills`、`nextState`），私有构造器 + 私有 getter（仅 ReactorStats 内部使用）。

### 7.3 ReactorFactory

反应堆模板工厂：解析 Reactor.wz 的 `<id>.img`，构建并缓存 `ReactorStats`；处理 `info/link`（模板复用）（源码路径：`server/maps/ReactorFactory.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `data` | `static DataProvider` | Reactor.wz |
| `reactorStats` | `static Map<Integer, ReactorStats>` | 进程级模板缓存（rid 与 link 目标 id 双写） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static ReactorStats getReactorS(int rid)` | **简化版加载**（CPQ 守卫塔用） | 缓存查询；link 跳转；逐状态号 i：读 `i/event/0`，type==100 时设 TL/BR；`addState(i, type, reactItem, state, timeOut, canTouch 位)`；无 event 的状态登记 999 型（终态或跳下一状态）；缓存双写 |
| `public static ReactorStats getReactor(int rid)` | **完整版加载**（主路径） | 缓存查询；link 跳转；`activateByTouch` 决定是否重设区域；逐状态 i：读 `i/event` 下全部事件子节点（Nexon 混排），逐个解析 type==100 投喂物+区域、`activeSkillID` 列表、state 下一状态，聚成 `List<StateData>` 后 `addState(i, list, timeOut)`；无 `0` 节点的摆设型反应堆（扎昆/帕普拉图斯门）登记单个 999 型状态；缓存双写 |

两者差异：`getReactorS` 只读 `event/0` 且不保留 activeSkills/超时登记；`getReactor` 读全部事件子节点并保留技能过滤与超时。

---

## 8. 空间技能实体（门 / 召唤 / 龙坐骑 / 雾 / 风筝）

### 8.1 Door

玩家「魔法门」聚合体：一次开门 = 城镇门 + 区域门两个 `DoorObject` 的成对组合，含部署校验与过期拆除（源码路径：`server/maps/Door.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ownerId` | `int` | 开门人 cid；构造失败时写入负数错误码（见下） |
| `town` / `townPortal` | `MapleMap`/`Portal` | 城镇侧图与占用的门位 |
| `target` | `MapleMap`（final） | 开门人所在图 |
| `posStatus` | `Pair<String,Integer>` | 严格门位模式下的偏离描述（方位, 距离） |
| `deployTime` / `active` | `long`/`boolean` | 部署时刻 / 有效性 |
| `townDoor` / `areaDoor` | `DoorObject` | 城镇侧 / 区域侧门对象（互指 pairOid） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Door(Character owner, Point targetPosition)` | 构造并尝试部署 | ① `target.canDeployDoor` 失败 → `ownerId=-2`；② 配置 `use_enforce_mystic_door_position` 时取 `getDoorPositionStatus`，非 null（偏离开阔地）→ `ownerId=-3`；③ 正常：town=返回城镇图，townPortal=按 `owner.getDoorSlot()` 取 0x80+slot 门位，记部署时间置 active；门位不存在 → `ownerId=-1`；④ 建 areaDoor（城镇图上、从门位进目标图）与 townDoor（目标图上、回门位），互相 setPairOid |
| `public void updateDoorPortal(Character owner)` | 更新门位（队伍门同步） | `owner.fetchDoorSlot()` 后换 townPortal 并 `areaDoor.update` |
| `private void broadcastRemoveDoor(Character owner)` | 拆除广播 | 两图各自移除门对象并对图内玩家发 destroy+移除可见；owner 移除队伍门；若占用的是 0x80 主门位，对仍在城镇且有主门的其他玩家重新下发本图 townDoor |
| `public static void attemptRemoveDoor(Character owner)` | 尝试关门（buff 到期/离线） | `dispose()` 首次成功后：部署特效 3s 未播完则经 OverallService 延时拆（防瞬关崩客户端），否则立即拆 |
| `private Portal getTownDoorPortal(int doorid)` | 取城镇门位 | `town.getDoorPortal(doorid)` |
| `public int getOwnerId()` / `getTownDoor()` / `getAreaDoor()` / `getTown()` / `getTownPortal()` / `getTarget()` / `getDoorStatus()` / `getElapsedDeployTime()` / `isActive()` | 取值 | ownerId 负数为部署失败码（-1 无门位 / -2 地面不符 / -3 位置受限于严格模式） |
| `private boolean dispose()` | 一次性置失效 | active 翻转，返回是否首次 |

### 8.2 DoorObject

单扇门的地图对象（`MapObjectType.DOOR`）：持来源图/目标图与联动门位，读写锁保护联动信息（源码路径：`server/maps/DoorObject.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ownerId`/`pairOid` | `int` | 主人 cid / 对侧门 oid |
| `from`/`to` | `MapleMap`（final） | 所在图 / 传送目标图 |
| `linkedPortalId`/`linkedPos` | `int`/`Point` | 联动城镇门位 ID / 对侧坐标（受读写锁保护） |
| `rlock`/`wlock` | `Lock` | 公平读写锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public DoorObject(int owner, MapleMap destination, MapleMap origin, int townPortalId, Point targetPosition, Point toPosition)` | 构造 | 落位 targetPosition；destination=to、origin=from |
| `public void update(int townPortalId, Point toPosition)` | 更新联动门位 | 写锁内更新 |
| `private int getLinkedPortalId()` / `private Point getLinkedPortalPosition()` | 持锁读联动信息 | — |
| `public void warp(Character chr)` | 玩家穿门 | 本人或与主人同队才可：播传送音效；非城镇门且无队 → `changeMap(to, 门位ID)`，否则 `changeMap(to, 联动坐标)`；无权限发 blockedMessage(6)+enableActions |
| `@Override public void sendSpawnData(Client client)` / `public void sendSpawnData(Client client, boolean launched)` | 下发门 | 仅当观者在 from 图：同队/本人先发 partyPortal；spawnPortal + 非城镇门补 spawnDoor（launched 区分开门动画） |
| `@Override public void sendDestroyData(Client client)` / `public void sendDestroyData(Client client, boolean partyUpdate)` | 下发拆门 | 在 from 图才发：同队/本人清 partyPortal；removeDoor |
| `public boolean inTown()` | 是否城镇侧门 | `linkedPortalId == -1`（城镇门无门位回链） |
| `public MapleMap getFrom()`/`getTo()`/`getTown()`/`getArea()` | 图关系 | town=城镇侧、area=区域侧 |
| `public Point getAreaPosition()` / `toPosition()` | 区域侧坐标 / 对侧坐标 | — |
| `public void setPairOid(int oid)` / `public int getPairOid()` | 对侧 oid | — |
| `@Override public MapObjectType getType()` | 类型 | `DOOR` |

### 8.3 Summon

玩家召唤兽地图对象（傀儡/乌鸦/火精灵等）：依附技能与技能等级，可被打（hp）（源码路径：`server/maps/Summon.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `owner` / `skill` / `skillLevel` | `Character`/`int`/`byte`（final） | 主人 / 技能 ID / 等级（构造时从主人技能表取，0 则抛 RuntimeException） |
| `hp` | `int` | 召唤兽 HP |
| `movementType` | `SummonMovementType`（final） | 移动模式 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Summon(Character owner, int skill, Point pos, SummonMovementType movementType)` | 构造 | — |
| `public Character getOwner()` / `public int getSkill()` / `public byte getSkillLevel()` / `public SummonMovementType getMovementType()` | 取值 | — |
| `public int getHP()` / `public void addHP(int delta)` | HP 存取 | — |
| `public boolean isStationary()` | 是否定驻 | 3111002（狙击手傀儡）/3211002（神射手傀儡）/5211001（章鱼炮台）/13111004（夜行者傀儡） |
| `public final boolean isPuppet()` | 是否傀儡 | 3111002/3211002/13111004 |
| `@Override sendSpawnData(Client)` / `sendDestroyData(Client)` | 数据包 | spawnSummon / removeSummon |
| `@Override public MapObjectType getType()` | 类型 | `SUMMON` |

### 8.4 SummonMovementType（枚举）

召唤兽移动模式：`STATIONARY(0)` 定驻、`FOLLOW(1)` 跟随、`CIRCLE_FOLLOW(3)` 环绕跟随；`getValue()` 取协议值。

### 8.5 Dragon

龙骑士坐骑龙（Evan/龙类）地图对象：与主人同 oid（`getObjectId()` 覆写返回 `owner.getId()`），构造即向主人客户端下发 spawn（源码路径：`server/maps/Dragon.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Dragon(Character chr)` | 构造 | 继承主人位置/姿态并立即 `sendSpawnData` |
| `@Override public MapObjectType getType()` | 类型 | `DRAGON` |
| `@Override public int getObjectId()` | 覆写取主人 cid 作 oid | 使客户端按玩家 id 关联龙 |
| `@Override public void sendSpawnData(Client)` / `sendDestroyData(Client)` | 数据包 | spawnDragon / removeDragon(ownerId) |
| `public Character getOwner()` | 取主人 | — |

### 8.6 Mist

区域雾地图对象（毒雾/烟幕/恢复光环，或怪物施放的雾）：不可移动、持矩形区域，分「玩家技能源」与「怪物技能源」两类构造（源码路径：`server/maps/Mist.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `mistPosition` | `Rectangle`（final） | 雾覆盖矩形 |
| `owner` / `mob` / `source` / `skill` | `Character`/`Monster`/`StatEffect`/`MobSkill` | 玩家源 / 怪物源（二选一） |
| `isMobMist` / `isPoisonMist` / `isRecoveryMist` | `boolean` | 怪物雾 / 毒雾 / 恢复雾 |
| `skillDelay` | `int` | 生效延迟（玩家源 8s，怪物源 0） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Mist(Rectangle mistPosition, Monster mob, MobSkill skill)` | 怪物雾构造 | 默认按毒雾处理 |
| `public Mist(Rectangle mistPosition, Character owner, StatEffect source)` | 玩家雾构造 | 按技能 ID 分类：`Evan.RECOVERY_AURA`→恢复雾；`Shadower.SMOKE_SCREEN`→非毒（烟幕）；`FPMage.POISON_MIST`/`BlazeWizard.FLAME_GEAR`/`NightWalker.POISON_BOMB`→毒雾 |
| `public Skill getSourceSkill()` | 技能对象 | `SkillFactory.getSkill(source.getSourceId())` |
| `public boolean isMobMist()` / `isPoisonMist()` / `isRecoveryMist()` / `getSkillDelay()` / `getMobOwner()` / `getOwner()` / `getBox()` | 取值 | — |
| `public final Packet makeSpawnData()` / `makeFakeSpawnData(int level)` / `makeDestroyData()` | 数据包 | 玩家源发 spawnMist（带等级，fake 用伪装等级 30），怪物源发 spawnMobMist |
| `@Override sendSpawnData/sendDestroyData` | 收发 | — |
| `public boolean makeChanceResult()` | 掷几率（毒雾每跳判定） | 委托 `source.makeChanceResult()` |
| `@Override public MapObjectType getType()` | 类型 | `MIST` |
| `@Override setPosition(Point)` | 禁止移动 | 抛 `UnsupportedOperationException` |

### 8.7 Kite

风筝（商店留言牌类道具）地图对象：固定主人位置，不可移动（源码路径：`server/maps/Kite.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pos`/`ft` | `Point`/`int` | 主人站位与 foothold |
| `owner`/`text`/`itemid` | `Character`/`String`/`int` | 主人 / 留言 / 道具 ID |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Kite(Character owner, String text, int itemId)` | 构造 | 取主人位置与 fh |
| `public final Packet makeSpawnData()` / `makeDestroyData()` | 数据包 | spawnKite / removeKite |
| `@Override sendSpawnData/sendDestroyData`、`getType()`（`KITE`）、`getOwner()`、`getPosition()` | 常规 | `setPosition` 抛异常（位置固定） |

---

## 9. 玩家交互设施（商店 / 小游戏 / 迷你地下城）

### 9.1 PlayerShop

玩家摆摊商店（`MapObjectType.SHOP`）：1 店主 + 3 访客位，16 格商品，含交易扣税、黑名单与聊天记录（源码路径：`server/maps/PlayerShop.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `open` | `AtomicBoolean` | 营业中 |
| `owner` / `itemid` | `Character`/`int`（final） | 店主 / 摊位道具 |
| `visitors` | `Character[3]` | 访客位（visitorLock 保护） |
| `items` / `sold` | `List<PlayerShopItem>`/`List<SoldItem>` | 商品 / 成交记录 |
| `boughtnumber` | `int` | 售罄计数（全卖完自动关店） |
| `bannedList` / `chatLog` / `chatSlot` | `List<String>`/`List<Pair<Character,String>>`/`Map<Integer,Byte>` | 黑名单 / 聊天记录（上限 25 条，防刷） |
| `visitorLock` | `ReentrantLock(true)` | 访客数组锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public PlayerShop(Character owner, String description, int itemid)` | 构造 | 落位店主位置 |
| `public synchronized boolean visitShop(Character chr)` | 进店 | 黑名单拒绝（中文提示）；未营业拒绝；有空位且非访客 → `addVisitor`、绑定 `setPlayerShop`、下发商店 |
| `private void addVisitor(Character visitor)` | 入座 | 找空位 i：`setSlot(i)`、广播新访客、更新地图摊位框 |
| `public void removeVisitor(Character visitor)` | 访客离店（保序压缩） | 店主离店则整店移除；访客离店：后续位次前移补齐（逐个 remove+re-add 包避免客户端错位），最后 `broadcastRestoreToVisitors` 全量刷新 |
| `public void forceRemoveVisitor(Character visitor)` | 强制踢出（不做位次压缩） | — |
| `public void removeVisitors()` | 清场 | 全员发 shopErrorMessage(10,1) 后逐个 forceRemove，含店主 |
| `public boolean hasFreeSlot()` / `isOwner(Character)` / `isVisitor(Character)` / `getShopRoomInfo()` | 座位查询 | `getShopRoomInfo` 返回 `{当前人数, 总位 3}` |
| `public boolean addItem(PlayerShopItem item)` | 上架 | 上限 16 格 |
| `public void takeItemBack(int slot, Character chr)` | 店主下架 | 背包有位才回收（数量=单件×捆数），更新商店包 |
| `public boolean buy(Client c, int item, short quantity)` | **购买** | 持 items 锁：仅访客可买；索引/存在性/库存校验；装备不可批量；karma 标记转不可交易；持 visitorLock：总价=单价×数量（防溢出取 min）；买家钱够且店主 `canHoldMeso`：扣买家钱、**扣 `Trade.getFee` 交易税**后入店主钱袋；记 SoldItem 通知店主；库存递减，售罄标记；全部售罄则自动关店并中文提示；买家背包满/钱不够中文提示 |
| `private static boolean canBuy(Client c, Item newItem)` | 背包可入 | checkSpace + addFromDrop |
| `public void broadcast(Packet)` / `broadcastToVisitors(Packet)` / `broadcastRestoreToVisitors()` | 店内广播 | restore：先全员移除再重发商店面板并恢复聊天记录（访客位变动后） |
| `public void chat(Client c, String chat)` | 店内聊天 | 记录（>25 条裁剪）并广播 |
| `private void recoverChatLog()` / `clearChatLog()` | 聊天记录恢复/清空 | 按玩家 id 找回发言位次 |
| `public void closeShop()` | 关店 | 清聊天、清访客、地图广播移除摊位框 |
| `public void sendShop(Client c)` | 下发商店面板 | — |
| `public List<PlayerShopItem> getItems()` / `hasItem(int itemid)` / `sendAvailableBundles(int itemid)` | 商品查询 | 只读视图 / 是否在售 / 同 ID 商品列表 |
| `public List<SoldItem> getSold()` | 成交记录 | — |
| `public void banPlayer(String name)` / `public boolean isBanned(String name)` | 拉黑 | 在店则踢出 |
| `public Character getOwner()` / `getVisitors()` / `getDescription()`/`setDescription(String)` / `getItemId()` / `getChannel()` / `getMapId()` | 取值 | — |
| `@Override sendSpawnData/sendDestroyData`、`getType()` | 地图对象协议 | updatePlayerShopBox / removePlayerShopBox / `SHOP` |

内部类 `public class SoldItem`：成交记录（buyer、itemid、quantity、mesos）及其 getter。

### 9.2 PlayerShopItem

商店单格商品：物品 + 捆数 + 单价（`PlayerShop` 与 `HiredMerchant` 共用）（源码路径：`server/maps/PlayerShopItem.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `item` / `price` | `Item`/`int`（final） | 物品与单价 |
| `bundles` | `short` | 剩余捆数（成交递减） |
| `doesExist` | `boolean` | 售罄/下架标记 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public PlayerShopItem(Item item, short bundles, int price)` | 构造 | doesExist=true |
| `public void setDoesExist(boolean tf)` / `public boolean isExist()` | 存在标记 | — |
| `public Item getItem()` / `public short getBundles()` / `public void setBundles(short)` / `public int getPrice()` | 存取 | — |

### 9.3 HiredMerchant

雇佣商店（NPC 式摆摊，店主可离线）：1 店主 + 3 访客、商品/留言/成交落库（Fredrick 领取）、店主封号联动停业、访问历史与黑名单；本仓库在 Cosmic 基础上强化了关店幂等与「过期实例」处理（源码路径：`server/maps/HiredMerchant.java`，961 行）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `VISITOR_HISTORY_LIMIT=10` / `BLACKLIST_LIMIT=20` | `static final int` | 访问历史上限 / 黑名单上限 |
| `ownerId`/`itemId`/`channel`/`world`/`start` | final | 店主 cid / 摊道具 / 频道 / 世界 / 开张时间 |
| `ownerName`/`description` | `String` | 店主名 / 描述 |
| `items`/`messages`/`sold` | `List` | 商品 / 留言 / 成交（各自同步块） |
| `open`/`closing` | `AtomicBoolean` | 营业中 / 关店进行中（CAS 幂等） |
| `published` / `ownerBanned` / `detached` | `volatile boolean` | 已发布过 / 店主已封号 / 实例已注销（过期实例） |
| `map` | `MapleMap` | 所在图（关店后置 null） |
| `visitors` | `Visitor[3]` | 访客（record：chr+入店时刻） |
| `visitorHistory` | `LinkedList<PastVisitor>` | 最近 10 条到访记录（名字+时长） |
| `blacklist` | `LinkedHashSet<String>` | 黑名单（大小写敏感） |
| `visitorLock` | `ReentrantLock(true)` | 访客锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public HiredMerchant(Character owner, String desc, int itemId)` | 构造 | 记录店主/频道/世界/开张时间与所在图 |
| `public synchronized void visitShop(Character chr)` | 进店 | 已封号/关店中/已注销 → MiniRoomError(18)；店主进店：停业（`setOpen(false)`）清客后打开管理界面；他人：未营业 18、黑名单 17、满座 2；否则入座并下发商店；最后 `setHiredMerchant(this)` |
| `public boolean addVisitor(Character visitor)` | 入座 | 未营业 false；空位 i 入座（记时刻）、广播新访客、更新地图摊位框 |
| `public void removeVisitor(Character chr)` | 离座 | 找到位次后置空、记入访问历史、广播离座 |
| `public void broadcastToVisitorsThreadsafe(Packet)` / `private broadcastToVisitors(Packet)` | 店内广播 | 前者持 visitorLock |
| `public byte[] getShopRoomInfo()` | 摊位框人数 | 营业中=在座数；停业=满员显示 |
| `public void withdrawMesos(Character chr)` | 店主提取货款 | 停业/封号/注销保护；`chr.withdrawMerchantMesos()` |
| `public void takeItemBack(int slot, Character chr)` | 店主下架 | 仅停业且店主本人；回收后 `updateHiredMerchant`；配置 `use_enforce_merchant_save` 强制立即存档 |
| `public void buy(Client c, int item, short quantity)` | **购买** | items 锁内：营业/索引/库存/装备批量校验；karma 转；买家钱够且背包可入：扣钱、扣 `Trade.getFee` 税、记 SoldItem、库存递减售罄标记；配置 `use_announce_shop_item_sold` 时给在线店主发中文售出通知（含剩余数）；店主在线 `addMerchantMesos`，离线则直接 UPDATE characters.MerchantMesos；最后 `saveItems(false)` 落库 |
| `private static boolean canBuy(Client c, Item newItem)` | 背包可入 | 同 PlayerShop |
| `private int getQuantityLeft(int itemid)` | 同 ID 剩余总量 | 捆数×单件数量累计 |
| `private void announceItemSold(Item item, int mesos, int inStore)` | 售出通知 | 在线才发 `[Hired Merchant] ...` |
| `public void closeForBan()` / `public void forceClose()` | 封号关店 / 强制关店 | 均先锁店主客户端（与上架/取回串行）再 `forceCloseInternal`（前者先置 ownerBanned+open=false） |
| `private void forceCloseInternal()` | **关店核心（幂等）** | ① `closing` CAS 防重入；② 判本实例是否权威（世界注册表在册）；③ 权威且店主不接收退货时先 `saveItems(true)` 落库，**失败回滚 closing 等定时重试**；④ 频道注销、地图广播拆除摊位框并移除对象；⑤ 非权威（过期实例）：置 detached、清客、warn 日志后返回；⑥ 清客、封号则踢店主界面；⑦ 清商品置 detached、清店主 HasMerchant（在线 set / 离线 UPDATE）、世界注销、置空 map |
| `public void closeOwnerMerchant(Character chr)` | 店主主动关店 | 封号中转 `closeForBan`；CAS 成功走 `closeOwnerMerchantAfterClaim` |
| `private void closeShop(Client c, boolean timeout)` | 店主在场关店 | 拆摊、移除频道注册、清客、`check`（背包+归属校验，防 dupe）通过则整批退给店主；再 saveItems、清 HasMerchant、可选强制存档、清商品、世界注销、置 detached |
| `public boolean isClosedForBan()` / `isPublished()` / `isOpen()` / `isOwner(Character)` | 状态查询 | `isOpen = open && !ownerBanned && !closing && !detached` |
| `public boolean setOpen(boolean set)` | 开业 | 关店 false 恒成功；开业：持 items 锁并**实时查库校验账号封禁/临时封禁**（补封号与开店交错窗口），异常/封禁一律拒绝置 ownerBanned；成功置 published |
| `public boolean addItem(PlayerShopItem item)` | 上架 | 停业态拒绝；上限 16 |
| `public void clearItems()` / `clearInexistentItems()` | 清空 / 清售罄项 | 后者顺带落库 |
| `public List<PlayerShopItem> getItems()` / `hasItem(int)` / `sendAvailableBundles(int)` | 商品查询 | — |
| `public void sendMessage(Character chr, String msg)` | 店内留言 | 记录 `名字 : 内容` 并广播 |
| `public void clearMessages()` / `getMessages()` | 留言管理 | — |
| `public void saveItems(boolean shutdown) throws SQLException` | **商品落库** | detached 后跳过；收集（Item, 类型, 捆数）经 `ItemFactory.MERCHANT.saveItems` 全量保存并 `insertFredrickLog`（Fredrick 领取凭据） |
| `private static boolean check(Character chr, List<PlayerShopItem> items)` | 退货可行性 | `Inventory.checkSpotsAndOwnership` |
| `public List<PastVisitor> getVisitorHistory()` | 访问历史 | — |
| `public void addToBlacklist(String)` / `removeFromBlacklist(String)` / `getBlacklist()` / `private isBlacklisted(String)` | 黑名单 | 上限 20 |
| `public int getVisitorSlotThreadsafe(Character)` / `private getVisitorSlot(Character)` / `getVisitorCharacters()` / `removeAllVisitors()` / `removeOwner(Character)` | 访客管理 | 清客时逐个记入历史并通知维护中断 |
| `public String getOwner()`（店主名）/ `getOwnerId()` / `getDescription()`/`setDescription(String)` / `getItemId()` / `getChannel()` / `getMapId()` / `getMap()` / `getSold()` / `getMesos()`（恒 0，货款在角色字段） | 取值 | — |
| `public int getTimeOpen()` | 已开张「游戏日」 | `(now-start)/60000 / 1440 * 1318` 向上取整（启发式折算） |
| `@Override public MapObjectType getType()` / `sendSpawnData(Client)` / `sendDestroyData(Client)` | 地图对象协议 | `HIRED_MERCHANT`；spawn 下发摊位框，destroy 空 |

内部类型：`private record Visitor(Character chr, Instant enteredAt)`（在座访客）；`public record PastVisitor(String chrName, Duration visitDuration)`（到访历史）；`public class SoldItem`（成交记录，同 PlayerShop）。

### 9.4 MiniGame

五子棋（Omok）与记忆配对（Match Cards）小游戏房间（`MapObjectType.MINI_GAME`）：1 房主 1 挑战者，含比分、认输、平局与棋盘逻辑（源码路径：`server/maps/MiniGame.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `owner` / `visitor` / `password` / `description` | — | 房主 / 挑战者 / 密码 / 描述 |
| `GameType` | `MiniGameType` | UNDEFINED/OMOK/MATCH_CARD |
| `piece[250]` | `int[]` | 五子棋棋盘（15×15，下标 y*15+x+1） |
| `list4x3/list5x4/list6x5` | `List<Integer>` | 配对卡牌池（6/10/15 对） |
| `inprogress` | `int` | 位标志：bit0 对局中、bit1 房主拒平、bit2 挑战者拒平 |
| `ownerpoints/visitorpoints` / `matchestowin` | `int` | 单局局分 / 胜负局数 |
| `ownerscore/visitorscore` / `ownerforfeits/visitorforfeits` / `lastvisitor` | `int` | 总分 / 弃权数（≥4 次不再扣 50 分）/ 上一位挑战者（换了人清零比分） |
| `ownerquit/visitorquit` / `nextavailabletie` / `loser` / `firstslot` / `piecetype` | — | 赛后退出标记 / 平局刷分冷却（5 分钟）/ 败者 / 先手 / 棋子样式 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MiniGame(Character owner, String description, String password)` | 构造 | — |
| `public boolean checkPassword(String sentPw)` | 校验密码 | 空密码免验，忽略大小写 |
| `public boolean hasFreeSlot()` / `isOwner(Character)` / `isVisitor(Character)` | 座位查询 | — |
| `public void addVisitor(Character challenger)` | 挑战者入座 | 换了新挑战者则比分/弃权清零；按游戏类型向房主发新访客包并更新地图房间框 |
| `public void closeRoom(boolean forceClose)` | 关房 | 移除地图房间框、双方收 close 包、解绑双方 miniGame、清空引用 |
| `public void removeVisitor(boolean forceClose, Character challenger)` | 挑战者离席 | 对局中离席判房主胜（弃权）；广播移除并恢复房间框为 1 人 |
| `public void broadcastToOwner(Packet)` / `broadcastToVisitor(Packet)` / `broadcast(Packet)` | 房内定向广播 | — |
| `public void minigameMatchStarted()` | 对局开始 | inprogress 置 1、清双方 quit 标记 |
| `public synchronized boolean minigameMatchFinish()`（private）/ `private void minigameMatchFinished()` | 结束对局 | 位清零返回是否有效；结束后处理 pending 的退出请求 |
| `public boolean isMatchInProgress()` | 对局中 | — |
| `public void setQuitAfterGame(Character player, boolean quit)` | 赛后退出登记 | — |
| `public void denyTie(Character chr)` / `public boolean isTieDenied(Character chr)` | 拒绝平局 | inprogress 位标志互查（对方拒了自己就是被拒） |
| `public void minigameMatchOwnerWins(boolean forfeit)` | 房主胜 | `minigameMatchFinish` 防重；`setMiniGamePoints` 记战绩；赢家 +50（弃权超过 4 次不加）、输家弃权 ∓15、弃权计数；广播胜负包；`minigameMatchFinished` |
| `public void minigameMatchVisitorWins(boolean forfeit)` | 挑战者胜 | 对称逻辑 |
| `public void minigameMatchDraw()` | 平局 | 双方 +10，5 分钟冷却防刷分（`nextavailabletie`） |
| `public void setOwnerPoints()` / `setVisitorPoints()` | 局分累计（Match Card 用） | 累计到 `matchestowin` 局时裁定总胜负并清零 |
| `public void setMatchesToWin(int)` / `getMatchesToWin()` | 赛制设置 | — |
| `public void setPieceType(int)` / `getPieceType()` / `setFirstSlot(int)` / `getFirstSlot()` | 棋子样式/先手 | — |
| `public void setGameType(MiniGameType game)` | 设置游戏类型 | MATCH_CARD 时按赛制（6/10/15 局）生成对应卡牌对池 |
| `public MiniGameType getGameType()` / `public boolean isOmok()` | 类型查询 | — |
| `public void shuffleList()` / `public int getCardId(int slot)` | 洗牌 / 取槽位卡 | 按赛制选对应池 |
| `public void setLoser(int)` / `getLoser()` | 败者标记 | — |
| `public void chat(Client c, String chat)` | 房内聊天 | — |
| `public void sendOmok(Client c, int type)` / `sendMatchCard(Client c, int type)` | 下发游戏面板 | — |
| `public Character getOwner()` / `getVisitor()` / `getDescription()` / `getOwnerScore()` / `getVisitorScore()` / `getPassword()` | 取值 | — |
| `public void setPiece(int move1, int move2, int type, Character chr)` | **五子棋落子+胜负判定** | 空位落子并广播；`searchCombo`（横/竖/双斜向下）+`searchCombo2`（另一斜向）扫描五连；命中判胜（按落子者）、清盘；`searchCombo(x,y,type)` 检查横向（步长 1）、纵向（15）、斜（16）；`searchCombo2` 检查步长 14 的反向斜线 |
| `@Override sendSpawnData/sendDestroyData` | 空实现 | 房间靠地图框包表现 |
| `@Override public MapObjectType getType()` | 类型 | `MINI_GAME` |

内部枚举：`MiniGameType`（UNDEFINED(0)/OMOK(1)/MATCH_CARD(2)，`getValue()`）；`MiniGameResult`（WIN/LOSS/TIE）。

### 9.5 MiniDungeon

迷你地下城会话：登记进图玩家、到时全体踢回基础图（源码路径：`server/maps/MiniDungeon.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `players` | `List<Character>` | 在场玩家（公平锁保护） |
| `timeoutTask` | `ScheduledFuture<?>` | 到期关闭任务（null 表示已关） |
| `baseMap` / `expireTime` | `int`/`long` | 基础图 ID / 到期时间戳 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MiniDungeon(int base, long timeLimit)` | 构造 | `expireTime = timeLimit 秒`；注册到期 `close()`；再叠加 now 得绝对时间 |
| `public boolean registerPlayer(Character chr)` | 玩家进图登记 | 有剩余时间则发时钟；已关（task==null）返回 false；入列表 |
| `public boolean unregisterPlayer(Character chr)` | 玩家离图注销 | 移除时钟；列表移除，空则 `dispose` 返回 false；**队长离场立即整场关闭**（防止队伍滞留） |
| `public void close()` | 到期/强制关闭 | 全员 `changeMap(baseMap)` 后 `dispose`、task 置 null |
| `public void dispose()` | 释放 | 清列表、取消任务 |

### 9.6 MiniDungeonInfo（枚举）

12 个迷你地下城静态登记表：入口图 → 基础副本图 + 连续图数量（源码路径：`server/maps/MiniDungeonInfo.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `MiniDungeonInfo(int baseId, int dungeonId, int dungeons)`（私有构造） | — | 枚举项如 `CAVE_OF_MUSHROOMS(ANT_TUNNEL_2, CAVE_OF_MUSHROOMS_BASE, 30)` 等 12 项 |
| `public int getBase()` / `getDungeonId()` / `getDungeons()` | 取入口图 / 副本基础 ID / 图数 | — |
| `public static boolean isDungeonMap(int map)` | 是否任一地下城图 | 遍历检查 `dungeonId <= map <= dungeonId+dungeons` |
| `public static MiniDungeonInfo getDungeon(int map)` | 反查所属地下城 | 同上，无匹配返回 null |

---

## 10. 地图表现效果

### 10.1 MapEffect

地图横幅/天气效果（道具触发的滚动文字）：不可变消息+道具 ID，仅作数据载体与包工厂（源码路径：`server/maps/MapEffect.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `msg` / `itemId` / `active` | `String`/`int`/`boolean`（final，active 恒 true） | 消息 / 天气道具 ID |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapEffect(String msg, int itemId)` | 构造 | — |
| `public final Packet makeDestroyData()` | 效果消失包 | `removeMapEffect` |
| `public final Packet makeStartData()` | 效果开始包 | `startMapEffect(msg, itemId, active)` |
| `public void sendStartData(Client client)` | 直接下发开始包 | 进图补发用（`MapleMap.addPlayer`） |

### 10.2 MapleTVEffect

MapleTV（村庄大电视）广播：全服（世界）级独占播放，按世界记活跃位，超时自动释放（源码路径：`server/maps/MapleTVEffect.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ACTIVE` | `static final boolean[]` | 每个世界一个占用位（大小=世界数） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static synchronized boolean broadcastMapleTVIfNotActive(Character player, Character victim, List<String> messages, int tvType)` | 尝试播放（空闲才播） | 本世界未占用则 `broadcastTV(true, …)` 返回 true；占用返回 false |
| `private static synchronized void broadcastTV(boolean activity, int userWorld, List<String> message, Character user, int type, Character partner)` | 播放/收回 | 播放：置占用位、全服 `enableTV`+`sendTV`（type>2 时减 3 修正），按 tvType 定档时长（默认 15s / type4=30s / type5=60s）调度收回；收回：清占用位、全服 `removeTV` |

---

## 附：模块协作关系小结

- **加载链**：`Channel.getMapFactory() → MapManager.getMap(mapid) → MapFactory.loadMapFromWz(...)`——MapFactory 只做无状态装配（WZ + `plife` 表 + PlayerNPC + ReactorFactory/PortalFactory/LifeFactory），MapManager 负责每频道/每活动实例的缓存与生命周期（`updateMaps` 周期驱动 `respawn`/`mobMpRecovery`）。
- **运行链**：玩家与实体的一切动态行为集中在 `MapleMap`——以 `characters`/`mapobjects` 两张锁表为核心，派生「玩家进出→视野同步（sendObjectPlacement/movePlayer）」「怪物生成/击杀→仇恨（MonsterAggroCoordinator）」「掉落→过期/容量/归属/物品反应堆」三条主链；地图级延时任务统一走频道 `OverallService`（按 mapid 聚合）。
- **门与空间技能**：`Door`/`DoorObject` 成对挂两图；`Summon`/`Mist`/`Kite`/`Dragon` 均为薄实体，逻辑仍在技能/buff 层，本模块负责注册、可见性与定时回收。
- **商店体系**：`PlayerShop`（在线摊）与 `HiredMerchant`（离线店，Fredrick 结算）共享 `PlayerShopItem`；HiredMerchant 的关店路径（forceClose/closeOwnerMerchant/closeForBan）用 CAS + 权威实例判定保证幂等，商品与货款落库后由 `FredrickProcessor` 供店主领取。
